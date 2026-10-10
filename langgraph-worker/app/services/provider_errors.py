class ProviderStreamIncompleteError(RuntimeError):
    """A started response ended without a terminal event and must not be resubmitted."""
