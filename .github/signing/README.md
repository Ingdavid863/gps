# GPS3D precision test signing

David authorized a new installation and a new test certificate for version 0.24.

The test keystore is cached as `gps3d-precision-test-signing-v3`. Its durable owner backup is `GPS3D_Respaldo_Firma_Pruebas_2026.zip`. Use the backed-up `precision-test.keystore` if the CI cache expires. The alias and passwords are `androiddebugkey` / `android`.

`test-certificate.sha256` pins the identity. Do not generate a new key while this identity exists. The prepare script fails if the key is missing or has a different certificate.

CI recovery artifacts contain only an authenticated encrypted keystore. Recover them with `scripts/restore_test_signing_backup.py`, the recovery private PEM from the owner backup, and the downloaded encrypted artifact directory. No private signing key or recovery private key is stored in this repository.
