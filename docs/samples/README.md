# Samples

Here go the real responses from the cloud and the devices, **masked** with
`klimakontrol.session.mask()` before being saved.

They serve two purposes: to adapt the parsing to what the server actually sends (today the shape
of the responses is inferred from the SDK code, not observed), and to become test fixtures.

Naming convention: `<comando>-<esito>.json`, for example `login-ok.json`,
`sdkcontrol-get-ok.json`, `energy-day-ok.json`.

The `.json` files in this folder are excluded from the repo except this README: they contain data
from the system. Commit them only after checking that the masking has covered everything.
