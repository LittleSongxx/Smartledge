class GraphEntityLlmError(RuntimeError):
    def __init__(self, reason: str, status_code: int = 503, category: str = "PROTOCOL", **diagnostics):
        super().__init__(reason)
        self.status_code = status_code
        self.detail = {"schemaVersion": "graph-tool-error.v1", "errorCode": reason.split(":", 1)[0],
                       "category": category, **diagnostics}
