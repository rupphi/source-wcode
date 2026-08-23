# JavaFX release runbook

## Trước khi tạo tag

1. Xác nhận project version và `app.version` trong `pom.xml` giống nhau.
2. Chạy `node --test tools/*.test.mjs` và `./mvnw -B clean verify`.
3. Kiểm tra migration bằng database fixture 1.1.9; không dùng database thật của operator.
4. Kiểm tra không có secret/KIZ/database/WAL trong diff hoặc artifact.
5. Tạo tag đúng dạng `vMAJOR.MINOR.PATCH` từ nhánh mặc định.

## Release trust

Protected GitHub environment `release` cần:

- `WINDOWS_SIGNING_CERTIFICATE` và `WINDOWS_SIGNING_PASSWORD`;
- `UPDATE_MANIFEST_PRIVATE_KEY` và `UPDATE_MANIFEST_PUBLIC_KEY`;
- `UPDATE_SIGNING_PUBLISHER` khớp chính xác subject của certificate;
- `RELEASE_TOKEN` có `contents:write` trên `rupphi/relatest-wcode`.

Windows Upgrade UUID `D0FC7057-DA6C-3181-ADF9-C21DB2C9152A` là identity vĩnh viễn đã dùng cho
1.1.8/1.1.9. Không đổi UUID này ở `build.bat`, workflow hay installer tương lai.

## Sau khi workflow hoàn tất

1. Verify Authenticode của `WCode.exe` và `WCode.msi`.
2. Verify `checksums.sha256` và signed `update-manifest.json` khớp MSI cuối cùng.
3. Cài đè từ 1.1.9, xác nhận chỉ có một registration WCode và dữ liệu shop/history còn nguyên.
4. Mở app, kiểm tra Wildberries regression và Ozon read-only trước khi live mutation.
5. Chỉ đánh dấu release `latest` sau khi canary operator hoàn tất một flow đóng gói thực.

## Rollback

Không hạ schema bằng tay. Dùng snapshot đã verify do `LocalDataMigrationGate` tạo, và chỉ restore
khi app đã đóng cùng với app-data lock được giữ bởi recovery procedure. Luôn giữ lại installer và
checksum của bản N-1.
