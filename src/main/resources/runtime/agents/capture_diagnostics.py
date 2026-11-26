"""Bounded, byte-exact diagnostic snapshots; missing source data stays explicitly missing."""
import base64

RAW_LIMIT = 65536


def raw_fields(data=None, total=None, offset=0):
    if data is None:
        return dict(rawBody="", rawEncoding="base64", rawAvailable=False,
                    rawBytes=0, rawOffset=0, rawTruncated=False)
    total = len(data) if total is None else total
    sample = bytes(data[:RAW_LIMIT])
    return dict(rawBody=base64.b64encode(sample).decode("ascii"), rawEncoding="base64",
                rawAvailable=True, rawBytes=total, rawOffset=offset,
                rawTruncated=offset > 0 or total > len(sample))
