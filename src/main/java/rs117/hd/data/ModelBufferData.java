package rs117.hd.data;

public final class ModelBufferData {
	public int vertexOffset;
	public int uvOffset;
	public int vertexCount;
	public int renderBufferOffset;

	public ModelBufferData(int vertexOffset, int uvOffset, int vertexCount) {
		this.vertexOffset = vertexOffset;
		this.uvOffset = uvOffset;
		this.vertexCount = vertexCount;
	}

	public ModelBufferData() {}
}
