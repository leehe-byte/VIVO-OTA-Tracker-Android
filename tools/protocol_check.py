"""交叉校验：确认 Kotlin 端协议实现与 Python 参考实现完全一致。

用法：
    python tools/protocol_check.py
"""
import json
import sys
from pathlib import Path

# 参考实现（原 Python 项目）；可用 VOTA_PY_SRC 覆盖
PY_SRC = Path(r"D:\github-repo\VIVO-OTA-Tracker")
sys.path.insert(0, str(PY_SRC if PY_SRC.exists() else Path(__file__).resolve().parents[1]))

from VivoOtaTracker import (  # noqa: E402
    DeviceConfig,
    AES_IV,
    AES_KEY_KV1,
    AES_KEY_KV2,
    TOKEN_NATIVE,
    build_protocol_package,
    build_request_params,
    encrypt_to_jvq,
    decrypt_response,
    aes_encrypt,
    parse_update_response,
    join_params,
)

assert AES_IV.hex() == "047cd76d65d3b28b4ccc2c0246681aa6"
assert AES_KEY_KV1.hex() == "5da590863052089893199b2a901b6470"
assert AES_KEY_KV2.hex() == "836e75afddae728551ad22b2bae6ca57"
assert TOKEN_NATIVE == "jnisgmain_v2@com.bbk.updater"

# 1) 固定明文（PKCS7 后刚好一条 block）下的密文应当可复现
fixed = b"a" * 16
ct = aes_encrypt(fixed, 2)
print("fixed-plaintext-ciphertext:", ct.hex())

# 2) 空明文 → 一整个 padding block
print("empty-ciphertext:", aes_encrypt(b"", 2).hex())

# 3) 协议包结构
pkg = build_protocol_package(5, 2, TOKEN_NATIVE, ct)
print("header-len:", int.from_bytes(pkg[:2], "big"), "expected:", 16 + len(TOKEN_NATIVE))
print("package-len:", len(pkg))

# 4) jvq 前缀
jvq = encrypt_to_jvq("x=1", 2)
print("jvq-prefix:", jvq[:3], "len:", len(jvq))

# 5) 手机/平板参数派生
phone = build_request_params(DeviceConfig(device_type="phone", model_sw_ver="PD2408",
                                          device_model="V2408A", sw_version="16.1.16.5.W10",
                                          android_ver=16, is_full=True))
tablet = build_request_params(DeviceConfig(device_type="tablet", model_sw_ver="DPD2106",
                                           device_model="PA2170", sw_version="8.7.22",
                                           android_ver=14, is_full=False))
print("phone.swVer:", phone["swVer"])
print("phone.fullVer:", phone["fullVer"])
print("phone.version:", phone["version"])
print("tablet.swVer:", tablet["swVer"], "| dType:", tablet["dType"], "| ms:", tablet["ms"])
print("phone.param-order:", "&".join(list(phone.keys())[:8]))
print("tablet.param-order:", "&".join(list(tablet.keys())[:8]))

# 6) 响应解析（服务端返回的是紧凑 JSON，冒号后没有空格）
sample = json.dumps({
    "message": "ok",
    "version": "16.2.13.0.W10",
    "pkName": "PD2324_A_16.2.13.0.W10-update.zip",
    "pkLen": "538968064",
    "h5Url": "https://example.com/a.html",
}, ensure_ascii=False, separators=(",", ":"))
info = parse_update_response(sample)
print("parsed:", info.version, info.filename, info.size, info.size_mb)
print("download:", info.download_url)
print("changelog:", info.changelog_url)

# 7) 解密往返
enc = aes_encrypt(sample.encode("utf-8"), 2)
round_trip = decrypt_response(
    __import__("base64").urlsafe_b64encode(
        build_protocol_package(5, 2, TOKEN_NATIVE, enc)
    ).decode(), 2)
assert round_trip == sample, "round trip mismatch"
print("round-trip: OK")
