/**
 * 
 */
package net.alternating.alternet;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;

import net.alternating.alternet.decoder.ObjectDecoder;
import processing.core.PApplet;

/**
 * @author Andreas Löf
 *
 */
public class ObjectClient extends Client {

	
    private ObjectDecoder d ;
	
	/**
	 * @param parent
	 * @param ip
	 * @param port
	 */
	public ObjectClient(PApplet parent, String ip, int port) {
		super(parent, ip, port);
		d = new ObjectDecoder(this);
	}

	public int send(byte[] data) {
		int length = data.length;
		byte[] lengthAsbytes = Utils.intToByteArray(length);
		super.send(lengthAsbytes);
		return super.send(data);
	}

	protected void decodeData(ByteBuffer bf, int length) throws CharacterCodingException {
		bf.flip();
		bf.rewind();
		d.decode(bf, length);
	}
	
	
	
	

}
