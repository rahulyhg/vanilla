/*
 * Copyright (C) 2013-2016 Adrian Ulrich <adrian@blinkenlights.ch>
 * Copyright (C) 2017 Google Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>. 
 */

package ch.blinkenlights.bastp;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Enumeration;



public class ID3v2File extends Common {
	private static final int ID3_ENC_LATIN   = 0x00;
	private static final int ID3_ENC_UTF16   = 0x01;
	private static final int ID3_ENC_UTF16BE = 0x02;
	private static final int ID3_ENC_UTF8    = 0x03;
	
	public ID3v2File() {
	}
	
	public HashMap getTags(RandomAccessFile s) throws IOException {
		HashMap tags = new HashMap();
		
		final int v2hdr_len = 10;
		byte[] v2hdr = new byte[v2hdr_len];
		
		// read the whole 10 byte header into memory
		s.seek(0);
		s.read(v2hdr);
		
		int v3minor = ((b2be32(v2hdr,0))) & 0xFF;   // swapped ID3\04 -> ver. ist the first byte
		int v3len   = ((b2be32(v2hdr,6)));          // total size EXCLUDING the this 10 byte header
		v3len       = unsyncsafe(v3len);
		
		// debug(">> tag version ID3v2."+v3minor);
		// debug(">> LEN= "+v3len+" // "+v3len);
		
		// we should already be at the first frame
		// so we can start the parsing right now
		tags = parse_v3_frames(s, v3len, v3minor);
		tags.put("_hdrlen", v3len+v2hdr_len);
		return tags;
	}

	/*
	**  converts syncsafe integer to Java integer
	*/
	private int unsyncsafe(int x) {
		x     = ((x & 0x7f000000) >> 3) |
				((x & 0x007f0000) >> 2) |
				((x & 0x00007f00) >> 1) |
				((x & 0x0000007f) >> 0) ;
		return x;
	}
	
	/* Parses all ID3v2 frames at the current position up until payload_len
	** bytes were read
	*/
	public HashMap parse_v3_frames(RandomAccessFile s, long payload_len, int v3minor) throws IOException {
		HashMap tags = new HashMap();
		byte[] frame   = new byte[10]; // a frame header is always 10 bytes
		long bread     = 0;            // total amount of read bytes

		while(bread < payload_len) {
			bread += s.read(frame);
			String framename = new String(frame, 0, 4);
			int rawlen = b2be32(frame, 4);
			// Encoders prior ID3v2.4 did not encode the frame length
			int slen = (v3minor >= 4 ? unsyncsafe(rawlen) : rawlen);
			
			/* Abort on silly sizes */
			long bytesRemaining = payload_len - bread;
			if(slen < 1 || slen > (bytesRemaining))
				break;
			
			byte[] xpl = new byte[slen];
			bread += s.read(xpl);
			
			if(framename.substring(0,1).equals("T")) {
				String[] nmzInfo = normalizeTaginfo(framename, xpl);
				String oggKey = nmzInfo[0];
				String decPld = nmzInfo[1];
				
				if(oggKey.length() > 0 && !tags.containsKey(oggKey)) {
					addTagEntry(tags, oggKey, decPld);
				}
			}
			else if(framename.equals("RVA2")) {
				//
			}
			
		}
		return tags;
	}
	
	/* Converts ID3v2 sillyframes to OggNames */
	private String[] normalizeTaginfo(String k, byte[] v) {
		String[] rv = new String[] {"",""};
		HashMap lu = new HashMap<String, String>();
		lu.put("TIT2", "TITLE");
		lu.put("TALB", "ALBUM");
		lu.put("TPE1", "ARTIST");
		lu.put("TPE2", "ALBUMARTIST");
		lu.put("TYER", "YEAR");
		lu.put("TPOS", "DISCNUMBER");
		lu.put("TRCK", "TRACKNUMBER");
		lu.put("TCON", "GENRE");
		lu.put("TCOM", "COMPOSER");
		
		if(lu.containsKey(k)) {
			/* A normal, known key: translate into Ogg-Frame name */
			rv[0] = (String)lu.get(k);
			rv[1] = getDecodedString(v);
		}
		else if(k.equals("TXXX")) {
			/* A freestyle field, ieks! */
			String txData[] = getDecodedString(v).split(Character.toString('\0'), 2);
			/* Check if we got replaygain info in key\0value style */
			if(txData.length == 2 && txData[0].matches("^(?i)REPLAYGAIN_(ALBUM|TRACK)_GAIN$")) {
				rv[0] = txData[0].toUpperCase(); /* some tagwriters use lowercase for this */
				rv[1] = txData[1];
			}
		}
		
		return rv;
	}
	
	/* Converts a raw byte-stream text into a java String */
	private String getDecodedString(byte[] raw) {
		int encid = raw[0] & 0xFF;
		int skip  = 1;
		String cs = "ISO-8859-1";
		String rv  = "";
		try {
			switch (encid) {
				case ID3_ENC_UTF8:
					cs = "UTF-8";
					break;
				case ID3_ENC_UTF16BE:
					cs = "UTF-16BE";
					skip = 3;
					break;
				case ID3_ENC_UTF16:
					cs = "UTF-16";
					if (raw.length > 4) {
						if ((raw[1]&0xFF) == 0xFE && (raw[2]&0XFF) == 0xFF && (raw[3]&0xFF) == 0x00 && (raw[4]&0xFF) == 0x00) {
							// buggy tag written by lame?!
							raw[3] = raw[2];
							raw[4] = raw[1];
							skip = 3;
						} else if((raw[1]&0xFF) == 0xFF && (raw[2]&0XFF) == 0x00 && (raw[3]&0xFF) == 0xFE) {
							// ?!, but seen in the wild
							raw[2] = raw[1];
							skip = 2;
						}
					}
					break;
				case ID3_ENC_LATIN:
				default:
					// uses defaults
			}

			rv = new String(raw, skip, raw.length-skip, cs);

			if (rv.length() > 0 && rv.substring(rv.length()-1).equals("\0")) {
				// SOME tag writers seem to null terminate strings, some don't...
				rv = rv.substring(0, rv.length()-1);
			}
		} catch(Exception e) {}
		return rv;
	}
	
}
