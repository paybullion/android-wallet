package com.paybullion.util.bip38;

/*
 * Copyright 2013 Megion Research & Development GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.paybullion.util.bip38.ec.EcTools;
import com.paybullion.util.bip38.ec.Parameters;
import com.paybullion.util.bip38.ec.Point;
import com.paybullion.util.bip38.util.ByteReader;
import com.paybullion.util.bip38.util.ByteWriter;


public class Signatures {

    private static final byte[] HEADER = "Bitcoin Signed Message:\n".getBytes(Charsets.UTF_8);
    private static final byte[] SIGNING_HEADER = standardSigningHeader();

    public static Signature decodeSignatureParameters(ByteReader reader) {
        byte[][] bytes = decodeSignatureParameterBytes(reader);
        if(bytes == null){
            return null;
        }

        // Make sure BigInteger regards it as positive
        bytes[0] = makePositive(bytes[0]);
        bytes[1] = makePositive(bytes[1]);
        return new Signature(new BigInteger(bytes[0]),new BigInteger(bytes[1]));
    }

    public static byte[][] decodeSignatureParameterBytes(ByteReader reader) {
        try {
            // Read tag, must be 0x30
            if ((((int) reader.get()) & 0xFF) != 0x30) {
                return null;
            }

            // Read total length as a byte, standard inputs never get longer than
            // this
            int length = ((int) reader.get()) & 0xFF;

            // Read first type, must be 0x02
            if ((((int) reader.get()) & 0xFF) != 0x02) {
                return null;
            }

            // Read first length
            int length1 = ((int) reader.get()) & 0xFF;

            // Read first byte array
            byte[] bytes1 = reader.getBytes(length1);

            // Read second type, must be 0x02
            if ((((int) reader.get()) & 0xFF) != 0x02) {
                return null;
            }

            // Read second length
            int length2 = ((int) reader.get()) & 0xFF;

            // Read second byte array
            byte[] bytes2 = reader.getBytes(length2);

            // Validate that the lengths add up to the total
            if (2 + length1 + 2 + length2 != length) {
                return null;
            }

            byte[][] result = new byte[][] { bytes1, bytes2 };
            return result;
        } catch (ByteReader.InsufficientBytesException e) {
            return null;
        }
    }


    private static byte[] makePositive(byte[] bytes) {
        if (bytes[0] < 0) {
            byte[] temp = new byte[bytes.length + 1];
            System.arraycopy(bytes, 0, temp, 1, bytes.length);
            return temp;
        }
        return bytes;
    }

    static boolean verifySignature(byte[] message, Signature signature, Point Q) {
        BigInteger n = Parameters.n;
        BigInteger e = calculateE(n, message);
        BigInteger r = signature.r;
        BigInteger s = signature.s;

        // r in the range [1,n-1]
        if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(n) >= 0) {
            return false;
        }

        // s in the range [1,n-1]
        if (s.compareTo(BigInteger.ONE) < 0 || s.compareTo(n) >= 0) {
            return false;
        }

        BigInteger c = s.modInverse(n);

        BigInteger u1 = e.multiply(c).mod(n);
        BigInteger u2 = r.multiply(c).mod(n);

        Point G = Parameters.G;

        Point point = EcTools.sumOfTwoMultiplies(G, u1, Q, u2);

        BigInteger v = point.getX().toBigInteger().mod(n);

        return v.equals(r);
    }

    private static BigInteger calculateE(BigInteger n, byte[] message) {
        if (n.bitLength() > message.length * 8) {
            return new BigInteger(1, message);
        } else {
            int messageBitLength = message.length * 8;
            BigInteger trunc = new BigInteger(1, message);

            if (messageBitLength - n.bitLength() > 0) {
                trunc = trunc.shiftRight(messageBitLength - n.bitLength());
            }

            return trunc;
        }
    }

    @VisibleForTesting
    static byte[] formatMessageForSigning(String message) {
        byte[] messageBytes = message.getBytes(Charsets.UTF_8);
        ByteWriter writer = new ByteWriter(messageBytes.length + SIGNING_HEADER.length + 1);
        writer.putBytes(SIGNING_HEADER);
        writer.putCompactInt(message.length());
        writer.putBytes(messageBytes);
        return writer.toBytes();
    }

    private static byte[] standardSigningHeader() {
        ByteArrayOutputStream bos1 = new ByteArrayOutputStream();
        bos1.write(HEADER.length);
        try {
            bos1.write(HEADER);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos1.toByteArray();
    }


}