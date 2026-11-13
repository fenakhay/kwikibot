"""Compares release versions by their numbers, so that 1.10.0 comes after 1.9.0."""


def is_older(version: str, than: str) -> bool:
    """Whether [version] comes before [than], which is what keeps an older tag from rewinding `main`."""
    return _numbers(version) < _numbers(than)


def _numbers(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))
