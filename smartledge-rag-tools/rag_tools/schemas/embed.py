from pydantic import BaseModel, ConfigDict, Field


class EmbedRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    texts: list[str] = Field(default_factory=list)


class EmbedResponse(BaseModel):
    """向量化响应。

    `model` 与 `dimensions` 由服务端声明，调用方据此校验维度契约，
    不要在调用方另行硬编码模型名。
    """

    model: str = ""
    dimensions: int = 0
    embeddings: list[list[float]] = Field(default_factory=list)
