// 
// Decompiled by Procyon v0.6.0
// 

package com.liskovsoft.smartyoutubetv2.common.vot;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;

final class VotProtoUtils
{
    static final int STATUS_AUDIO_REQUESTED = 6;
    static final int STATUS_FAILED = 0;
    static final int STATUS_FINISHED = 1;
    static final int STATUS_LONG_WAITING = 3;
    static final int STATUS_PART_CONTENT = 5;
    static final int STATUS_SESSION_REQUIRED = 7;
    static final int STATUS_WAITING = 2;
    
    private VotProtoUtils() {
    }
    
    static SessionResponse decodeSessionResponse(final byte[] array) {
        final ProtoReader protoReader = new ProtoReader(array);
        String string = "";
        int varint32 = 0;
        while (protoReader.hasRemaining()) {
            final int varint33 = protoReader.readVarint32();
            final int n = varint33 >>> 3;
            if (n != 1) {
                if (n != 2) {
                    protoReader.skipField(varint33);
                }
                else {
                    varint32 = protoReader.readVarint32();
                }
            }
            else {
                string = protoReader.readString();
            }
        }
        return new SessionResponse(string, varint32);
    }
    
    static TranslationResponse decodeTranslationResponse(final byte[] array) {
        final ProtoReader protoReader = new ProtoReader(array);
        final TranslationResponse translationResponse = new TranslationResponse();
        while (protoReader.hasRemaining()) {
            final int varint32 = protoReader.readVarint32();
            final int n = varint32 >>> 3;
            boolean isLivelyVoice = true;
            if (n != 1) {
                if (n != 12) {
                    if (n != 4) {
                        if (n != 5) {
                            switch (n) {
                                default: {
                                    protoReader.skipField(varint32);
                                    continue;
                                }
                                case 10: {
                                    if (protoReader.readVarint32() == 0) {
                                        isLivelyVoice = false;
                                    }
                                    translationResponse.isLivelyVoice = isLivelyVoice;
                                    continue;
                                }
                                case 9: {
                                    translationResponse.message = protoReader.readString();
                                    continue;
                                }
                                case 8: {
                                    translationResponse.language = protoReader.readString();
                                    continue;
                                }
                                case 7: {
                                    translationResponse.translationId = protoReader.readString();
                                    continue;
                                }
                            }
                        }
                        else {
                            translationResponse.remainingTime = protoReader.readVarint32();
                        }
                    }
                    else {
                        translationResponse.status = protoReader.readVarint32();
                    }
                }
                else {
                    translationResponse.shouldRetry = protoReader.readVarint32();
                }
            }
            else {
                translationResponse.url = protoReader.readString();
            }
        }
        return translationResponse;
    }
    
    static byte[] encodeChunkAudioRequest(final String s, final String s2, final String s3, final int n, final int n2, final int n3, final byte[] array) {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeString(byteArrayOutputStream, 1, s2);
        writeString(byteArrayOutputStream, 2, s);
        final ByteArrayOutputStream byteArrayOutputStream2 = new ByteArrayOutputStream();
        writeInt32(byteArrayOutputStream2, 1, n);
        writeBytes(byteArrayOutputStream2, 2, array);
        final ByteArrayOutputStream byteArrayOutputStream3 = new ByteArrayOutputStream();
        writeMessage(byteArrayOutputStream3, 1, byteArrayOutputStream2.toByteArray());
        writeInt32(byteArrayOutputStream3, 2, n2);
        writeString(byteArrayOutputStream3, 3, s3);
        writeInt32(byteArrayOutputStream3, 4, n3);
        writeMessage(byteArrayOutputStream, 4, byteArrayOutputStream3.toByteArray());
        return byteArrayOutputStream.toByteArray();
    }
    
    static byte[] encodeSessionRequest(final String s, final String s2) {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeString(byteArrayOutputStream, 1, s);
        writeString(byteArrayOutputStream, 2, s2);
        return byteArrayOutputStream.toByteArray();
    }
    
    static byte[] encodeSingleAudioRequest(final String s, final String s2, final String s3, final byte[] array) {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeString(byteArrayOutputStream, 1, s2);
        writeString(byteArrayOutputStream, 2, s);
        final ByteArrayOutputStream byteArrayOutputStream2 = new ByteArrayOutputStream();
        writeString(byteArrayOutputStream2, 1, s3);
        writeBytes(byteArrayOutputStream2, 2, array);
        writeMessage(byteArrayOutputStream, 6, byteArrayOutputStream2.toByteArray());
        return byteArrayOutputStream.toByteArray();
    }
    
    static byte[] encodeTranslationRequest(final String s, final int n, final String s2, final String s3, final String s4, final boolean b, final boolean b2) {
        final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        writeString(byteArrayOutputStream, 3, s);
        writeBool(byteArrayOutputStream, 5, b);
        writeDouble(byteArrayOutputStream, 6, n);
        writeInt32(byteArrayOutputStream, 7, 1);
        writeString(byteArrayOutputStream, 8, s2);
        writeString(byteArrayOutputStream, 14, s3);
        writeInt32(byteArrayOutputStream, 15, 1);
        writeInt32(byteArrayOutputStream, 16, 2);
        writeBool(byteArrayOutputStream, 18, b2);
        if (s4 != null && !s4.isEmpty()) {
            writeString(byteArrayOutputStream, 19, s4);
        }
        return byteArrayOutputStream.toByteArray();
    }
    
    private static void writeBool(final ByteArrayOutputStream byteArrayOutputStream, final int n, final boolean b) {
        writeTag(byteArrayOutputStream, n, 0);
        writeVarint32(byteArrayOutputStream, b ? 1 : 0);
    }
    
    private static void writeBytes(final ByteArrayOutputStream byteArrayOutputStream, final int n, final byte[] b) {
        writeTag(byteArrayOutputStream, n, 2);
        writeVarint32(byteArrayOutputStream, b.length);
        byteArrayOutputStream.write(b, 0, b.length);
    }
    
    private static void writeDouble(final ByteArrayOutputStream byteArrayOutputStream, int i, final double value) {
        writeTag(byteArrayOutputStream, i, 1);
        final long doubleToLongBits = Double.doubleToLongBits(value);
        for (i = 0; i < 8; ++i) {
            byteArrayOutputStream.write((int)(doubleToLongBits >> i * 8 & 0xFFL));
        }
    }
    
    private static void writeInt32(final ByteArrayOutputStream byteArrayOutputStream, final int n, final int n2) {
        writeTag(byteArrayOutputStream, n, 0);
        writeVarint32(byteArrayOutputStream, n2);
    }
    
    private static void writeMessage(final ByteArrayOutputStream byteArrayOutputStream, final int n, final byte[] b) {
        writeTag(byteArrayOutputStream, n, 2);
        writeVarint32(byteArrayOutputStream, b.length);
        byteArrayOutputStream.write(b, 0, b.length);
    }
    
    private static void writeString(final ByteArrayOutputStream byteArrayOutputStream, final int n, final String s) {
        if (s == null) {
            return;
        }
        writeBytes(byteArrayOutputStream, n, s.getBytes(StandardCharsets.UTF_8));
    }
    
    private static void writeTag(final ByteArrayOutputStream byteArrayOutputStream, final int n, final int n2) {
        writeVarint32(byteArrayOutputStream, n << 3 | n2);
    }
    
    private static void writeVarint32(final ByteArrayOutputStream byteArrayOutputStream, final int n) {
        long n2;
        for (n2 = ((long)n & 0xFFFFFFFFL); (0xFFFFFFFFFFFFFF80L & n2) != 0x0L; n2 >>>= 7) {
            byteArrayOutputStream.write((int)((0x7FL & n2) | 0x80L));
        }
        byteArrayOutputStream.write((int)n2);
    }
    
    private static final class ProtoReader
    {
        private final byte[] data;
        private int pos;
        
        ProtoReader(byte[] data) {
            if (data == null) {
                data = new byte[0];
            }
            this.data = data;
        }
        
        boolean hasRemaining() {
            return this.pos < this.data.length;
        }
        
        byte[] readLengthDelimited() {
            final int varint32 = this.readVarint32();
            if (varint32 >= 0) {
                final int pos = this.pos;
                final byte[] data = this.data;
                if (pos + varint32 <= data.length) {
                    final byte[] array = new byte[varint32];
                    System.arraycopy(data, pos, array, 0, varint32);
                    this.pos += varint32;
                    return array;
                }
            }
            throw new IllegalStateException("Invalid protobuf length");
        }
        
        String readString() {
            return new String(this.readLengthDelimited(), StandardCharsets.UTF_8);
        }
        
        int readVarint32() {
            int n = 0;
            int n2 = 0;
            while (true) {
                final int pos = this.pos;
                final byte[] data = this.data;
                if (pos >= data.length) {
                    throw new IllegalStateException("Unexpected end of protobuf stream");
                }
                this.pos = pos + 1;
                final int n3 = data[pos] & 0xFF;
                n |= (n3 & 0x7F) << n2;
                if ((n3 & 0x80) == 0x0) {
                    return n;
                }
                n2 += 7;
                if (n2 <= 35) {
                    continue;
                }
                throw new IllegalStateException("Invalid protobuf varint");
            }
        }
        
        void skipField(int varint32) {
            varint32 &= 0x7;
            if (varint32 != 0) {
                if (varint32 != 1) {
                    if (varint32 != 2) {
                        if (varint32 != 5) {
                            final StringBuilder sb = new StringBuilder("Unsupported protobuf wire type: ");
                            sb.append(varint32);
                            throw new IllegalStateException(sb.toString());
                        }
                        this.pos += 4;
                    }
                    else {
                        varint32 = this.readVarint32();
                        this.pos += varint32;
                    }
                }
                else {
                    this.pos += 8;
                }
            }
            else {
                this.readVarint32();
            }
            if (this.pos <= this.data.length) {
                return;
            }
            throw new IllegalStateException("Unexpected end of protobuf stream");
        }
    }
    
    static final class SessionResponse
    {
        final int expires;
        final String secretKey;
        
        SessionResponse(final String secretKey, final int expires) {
            this.secretKey = secretKey;
            this.expires = expires;
        }
    }
    
    static final class TranslationResponse
    {
        boolean isLivelyVoice;
        String language;
        String message;
        int remainingTime;
        int shouldRetry;
        int status;
        String translationId;
        String url;
        
        TranslationResponse() {
            this.remainingTime = -1;
            this.shouldRetry = -1;
        }
    }
}
