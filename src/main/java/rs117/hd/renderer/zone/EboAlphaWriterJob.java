package rs117.hd.renderer.zone;

import java.nio.IntBuffer;
import java.util.ArrayDeque;
import lombok.extern.slf4j.Slf4j;
import rs117.hd.utils.buffer.GLMappedBufferIntWriter;
import rs117.hd.utils.jobs.Job;

@Slf4j
public final class EboAlphaWriterJob extends Job {
	public final ArrayDeque<Zone.AlphaModel> alphaModels = new ArrayDeque<>();
	public GLMappedBufferIntWriter.ReservedView eboAlphaView;
	public int[] sortedAlphaIndicies;

	@Override
	protected void onRun() {
		try {
			final IntBuffer eboAlphaBuffer = eboAlphaView.getBuffer();
			if (eboAlphaBuffer == null)
				return;

			int pendingOffset = -1;
			int pendingLen = 0;

			Zone.AlphaModel m;
			while ((m = alphaModels.poll()) != null) {
				if (m.sortedIndiciesCount <= 0)
					continue;

				final int offset = m.sortedIndiciesOffset;
				if (pendingLen > 0 && offset == pendingOffset + pendingLen) {
					pendingLen += m.sortedIndiciesCount;
					continue;
				}

				if (pendingLen > 0 && !flush(eboAlphaBuffer, pendingOffset, pendingLen))
					return;

				pendingOffset = offset;
				pendingLen = m.sortedIndiciesCount;
			}

			if (pendingLen > 0)
				flush(eboAlphaBuffer, pendingOffset, pendingLen);
		} finally {
			eboAlphaView = null;
		}
	}

	private boolean flush(IntBuffer eboAlphaBuffer, int offset, int len) {
		if (eboAlphaBuffer.remaining() < len) {
			log.warn("Not enough space in eboAlphaBuffer for alpha faces");
			return false;
		}
		eboAlphaBuffer.put(sortedAlphaIndicies, offset, len);
		return true;
	}
}
