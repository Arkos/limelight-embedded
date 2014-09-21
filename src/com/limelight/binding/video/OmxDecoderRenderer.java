package com.limelight.binding.video;

import com.limelight.LimeLog;
import com.limelight.nvstream.av.ByteBufferDescriptor;
import com.limelight.nvstream.av.DecodeUnit;

import java.util.List;

/**
 * Implementation of a video decoder and renderer.
 * @author Iwan Timmer
 */
public class OmxDecoderRenderer extends AbstractVideoRenderer {
	
	private final static byte[] BITSTREAM_RESTRICTIONS = new byte[] {(byte) 0xF1, (byte) 0x83, 0x2A, 0x00};

	@Override
	public boolean setup(int width, int height, int redrawRate, Object renderTarget, int drFlags) {
		return OmxDecoder.init() == 0;
	}

	@Override
	public void stop() {
		super.stop();
		OmxDecoder.stop();
	}

	@Override
	public void release() {
		OmxDecoder.destroy();
	}

	@Override
	public void decodeUnit(DecodeUnit decodeUnit) {
		List<ByteBufferDescriptor> units = decodeUnit.getBufferList();
		
		ByteBufferDescriptor header = units.get(0);
		if (header.data[header.offset+4] == 0x67) {
			byte last = header.data[header.length+header.offset-1];
		
			//Set number of reference frames back to 1 as it's the minimum for bitstream restrictions
			this.replace(header, 80, 9, new byte[] {0x40}, 3);

			//Set bitstream restrictions to only buffer single frame (starts 9 bits before stop bit and 6 bits earlier because of the shortening above)
			this.replace(header, header.length*8+Integer.numberOfLeadingZeros(last & - last)%8-9-6, 2, BITSTREAM_RESTRICTIONS, 3*8);
		}
		
		boolean ok = true;
		for (int i=0;i<units.size();i++) {
			ByteBufferDescriptor bbd = units.get(i);
			if (ok) {
				int ret = OmxDecoder.decode(bbd.data, bbd.offset, bbd.length, i == (units.size()-1));
				if (ret != 0) {
					LimeLog.severe("Error code during decode: " + ret);
					ok = false;
				}
			}
		}
	}
	
	/**
	 * Replace bits in array 
	 * @param source array in which bits should be replaced
	 * @param srcOffset offset in bits where replacement should take place
	 * @param srcLength length in bits of data that should be replaced
	 * @param data data array with the the replacement data
	 * @param dataLength length of replacement data in bits
	 */
	public void replace(ByteBufferDescriptor source, int srcOffset, int srcLength, byte[] data, int dataLength) {
		//Add 7 to always round up
		int length = (source.length*8-srcLength+dataLength+7)/8;

		int bitOffset = srcOffset%8;
		int byteOffset = srcOffset/8;
		
		byte dest[] = null;
		int offset = 0;
		if (length>source.length) {
			dest = new byte[length];
			
			//Copy the first bytes
			System.arraycopy(source.data, source.offset, dest, offset, byteOffset);
		} else {
			dest = source.data;
			offset = source.offset;
		}
		
		int byteLength = (bitOffset+dataLength+7)/8;
		int bitTrailing = 8 - (srcOffset+dataLength) % 8;
		for (int i=0;i<byteLength;i++) {
			byte result = 0;
			if (i != 0)
				result = (byte) (data[i-1] << 8-bitOffset);
			else if (bitOffset > 0)
				result = (byte) (source.data[byteOffset+source.offset] & (0xFF << 8-bitOffset));
			
			if (i == 0 || i != byteLength-1) {
				byte moved = (byte) ((data[i]&0xFF) >>> bitOffset);
				result |= moved;
			}
			
			if (i == byteLength-1 && bitTrailing > 0) {
				int sourceOffset = srcOffset+srcLength/8;
				int bitMove = (dataLength-srcLength)%8;
				if (bitMove<0) {
					result |= (byte) (source.data[sourceOffset+source.offset] << -bitMove & (0xFF >>> bitTrailing));
					result |= (byte) (source.data[sourceOffset+1+source.offset] << -bitMove & (0xFF >>> 8+bitMove));
				} else {
					byte moved = (byte) ((source.data[sourceOffset+source.offset]&0xFF) >>> bitOffset);
					result |= moved;
				}
			}
			
			dest[i+byteOffset+offset] = result;
		}
		
		//Source offset
		byteOffset += srcLength/8;
		bitOffset = (srcOffset+dataLength-srcLength)%8;
		
		//Offset in destination
		int destOffset = (srcOffset+dataLength)/8;
		
		for (int i=1;i<source.length-byteOffset;i++) {
			int diff = destOffset >= byteOffset-1?i:source.length-byteOffset-i;
			
			byte result = 0;
			result = (byte) (source.data[byteOffset+diff-1+source.offset] << 8-bitOffset);
			byte moved = (byte) ((source.data[byteOffset+diff+source.offset]&0xFF) >>> bitOffset);
			result ^= moved;

			dest[diff+destOffset+offset] = result;
		}
		
		source.data = dest;
		source.offset = offset;
		source.length = length;
	}

}
