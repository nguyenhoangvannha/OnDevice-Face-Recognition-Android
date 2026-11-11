# Python client mẫu

`xperia_face_client.py`: client cho REST API của Xperia coprocessor ([../../docs/coprocessor/api.md](../../docs/coprocessor/api.md)).
Chỉ dùng thư viện chuẩn Python 3.7+ (không cần `pip install`), hợp với TV box Armbian 1 GB RAM hoặc container nhỏ.

## Cấu hình (biến môi trường, không ghi vào code)

| Biến | Bắt buộc | Ví dụ |
| --- | --- | --- |
| `XPERIA_FACE_URL` | có | `http://<ip-điện-thoại>:8080` (IP lấy trên dashboard; **chưa chốt**) |
| `XPERIA_FACE_TOKEN` | có | token trên dashboard. Lưu trong file secret quyền `600`, không commit |
| `XPERIA_FACE_TIMEOUT` | không | `3.0` (giây, mặc định) |

## Dòng lệnh

```bash
export XPERIA_FACE_URL=http://<ip-điện-thoại>:8080
export XPERIA_FACE_TOKEN=...            # dán từ dashboard, không lưu vào lịch sử shell nếu được
python3 xperia_face_client.py health
python3 xperia_face_client.py recognize snapshot.jpg
python3 xperia_face_client.py recognize snapshot.jpg --person-box 0.41,0.22,0.12,0.55 --cluster-strangers
python3 xperia_face_client.py strangers
python3 xperia_face_client.py promote 3 --name "An"      # hoặc --person-id 9
python3 xperia_face_client.py delete-stranger 3
python3 xperia_face_client.py enroll "An" an1.jpg an2.jpg
python3 xperia_face_client.py add-images 1 an3.jpg an4.jpg
python3 xperia_face_client.py persons
python3 xperia_face_client.py delete 3
```

## Trong code

```python
from xperia_face_client import recognize_snapshot

result = recognize_snapshot(jpeg_bytes)   # None nếu timeout / mất mạng / lỗi API
if result:
    names = [f["name"] for f in result["faces"] if f["recognized"]]
```

Recognize v2 (xem api.md): `client.recognize(img, person_box=event["data"]["box"], cluster_strangers=True)`.
`person_box` là `[x, y, w, h]` chuẩn hoá 0..1 (đúng định dạng `data.box` của Frigate). Người lạ có
`is_stranger=true`, `name="Person_NN"`, nhưng `recognized` vẫn `false`. Quản lý: `strangers()`,
`promote_stranger(id, name=... | person_id=...)`, `delete_stranger(id)`, `delete_all_strangers()`,
`stranger_settings()`, `set_stranger_threshold(0.7)`.

`recognize_snapshot` không bao giờ ném lỗi: `None` nghĩa là "không biết", luồng cảnh báo phải chạy tiếp như người lạ.
`XperiaFaceClient` ném `CoprocessorError` (có `status`, `error`) khi cần xử lý chi tiết. `repr()` không chứa token.

## Test

```bash
cd clients/python && python3 -m unittest -v test_xperia_face_client
```

Test chạy với server giả cục bộ, không cần điện thoại.

## Chưa làm

Chưa tích hợp vào Frigate/notifier nào. Nơi chạy client (notifier trên Mac Mini hay Armbian Phố) là câu hỏi mở,
xem [../../docs/coprocessor/README.md](../../docs/coprocessor/README.md#câu-hỏi-tích-hợp-còn-mở).
