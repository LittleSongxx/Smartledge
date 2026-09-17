from pydantic import BaseModel, ConfigDict, Field


class GraphCommunityEdge(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    source: str
    target: str


class GraphCommunityRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    nodes: list[str] = Field(default_factory=list)
    edges: list[GraphCommunityEdge] = Field(default_factory=list)


class GraphCommunityResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True)

    method: str
    membership: dict[str, str] = Field(default_factory=dict)
