/*
 *******************************************************************************
 * Adapted from:
 *
 *   Android U2F USB Bridge
 *   (c) 2016 Ledger
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *   limitations under the License.
 ********************************************************************************/

package to.crp.android.u2f;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class U2FHelper {

    private static final String TAG = "okd-helper";

    private static final int CHANNEL_LENGTH = 4;

    private static final int FRST_PKT_HDR_LEN = 7;
    private static final int CONT_PACKET_HEADER_LENGTH = 5;

    private static final int CHANNEL_BROADCAST = 0xffffffff;

    /**
     * Current logical channel for this application's usage of the U2F device.
     */
    private int channel = CHANNEL_BROADCAST;

    /**
     * @return The channel being used by this application's connection to the U2F device.
     */
    int getChannel() {
        return channel;
    }

    /**
     * Set the U2F device channel.
     *
     * @param channel device channel
     */
    void setChannel(final int channel) {
        this.channel = channel;
    }

    /**
     * Generate HID packet(s) required to send the payload with the given command.
     *
     * @param cmdId      command identifier
     * @param payload    payload data
     * @param packetSize HID packet size (bytes)
     * @return HID packets containing the payload data
     */
    byte[] wrapCommandAPDU(final byte cmdId, final byte[] payload, final int packetSize) {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        int sequenceIdx = 0;
        int offset = 0;

        // transaction id - 4 bytes
        output.write(channel >> 24);
        output.write(channel >> 16);
        output.write(channel >> 8);
        output.write(channel);

        // command - 1 byte
        output.write(cmdId);

        // length - 2 bytes
        output.write(payload.length >> 8);
        output.write(payload.length);

        int blockSize = (payload.length > packetSize - FRST_PKT_HDR_LEN ?
                packetSize - FRST_PKT_HDR_LEN : payload.length);
        output.write(payload, offset, blockSize);
        offset += blockSize;
        while (offset != payload.length) {
            output.write(channel >> 24);
            output.write(channel >> 16);
            output.write(channel >> 8);
            output.write(channel);
            output.write(sequenceIdx++);
            blockSize = (payload.length - offset > packetSize - CONT_PACKET_HEADER_LENGTH ?
                    packetSize - CONT_PACKET_HEADER_LENGTH : payload.length - offset);
            output.write(payload, offset, blockSize);
            offset += blockSize;
        }
        if ((output.size() % packetSize) != 0) {
            byte[] padding = new byte[packetSize - (output.size() % packetSize)];
            output.write(padding, 0, padding.length);
        }
        return output.toByteArray();
    }

    /**
     * Get the 4-byte channel value from byte[] at index.
     *
     * @param data   byte[] containing channel information.
     * @param offset where in data to find the channel
     * @return channel value
     */
    private int getChannel(final byte[] data, final int offset) {
        return ((data[offset] & 0xff) << 24) | ((data[offset + 1] & 0xff) << 16) | ((data[offset
                + 2] & 0xff) << 8) | (data[offset + 3] & 0xff);
    }

    /**
     * Verify the buffer is a valid size for the claimed payload length.
     *
     * @param buffer     buffer to validate
     * @param packetSize HID packet size
     * @throws IOException If the buffer does not represent a complete HID packet(s).
     */
    public void verifyBuffer(final byte[] buffer, final int packetSize) throws IOException {
        if (buffer == null) {
            throw new IOException("No data received.");
        }

        if (buffer.length == 0) {
            throw new IOException("Buffer is empty.");
        }

        if (buffer.length % packetSize != 0) {
            throw new IOException("Buffer is not divisible by the packet size. " +
                    "Buffer: " + buffer.length + " Packet Size: " + packetSize);
        }

        //Log.d(TAG, "Buffer length: "+buffer.length);

        int payloadLength = ((buffer[5] & 0xff) << 8);
        payloadLength |= (buffer[6] & 0xff);

        // check we don't have less data than claimed
        final int firstPacketDataLength = packetSize - FRST_PKT_HDR_LEN;
        int expectedBufferLength;

        if (payloadLength <= firstPacketDataLength) {
            Log.d(TAG, "Payload is contained in the first packet.");
            expectedBufferLength = packetSize;
        } else {

            final int continuationPayload = payloadLength - firstPacketDataLength;
            Log.d(TAG,
                    "Payload is across multiple packets. Continuation payload len: " +
                            continuationPayload);

            int numPackets = 1 + (continuationPayload / (packetSize - CONT_PACKET_HEADER_LENGTH));
            if (continuationPayload % packetSize != 0) {
                numPackets++;
            }

            Log.d(TAG, "Num packets: " + numPackets);

            expectedBufferLength = packetSize * numPackets;
        }

        if (buffer.length != expectedBufferLength) {
            throw new IOException(
                    "Payload not complete (" + buffer.length + "/" + expectedBufferLength + " " +
                            "bytes). Payload: " + payloadLength);
        }
    }

    /**
     * Return null until we can completely parse a multi-packet response sans error.
     *
     * @param cmdId      expected command identifier
     * @param apdu       APDU
     * @param packetSize size of  complete packet (response may contain more than one packet)
     * @return contents of response APDU
     * @throws IOException Thrown on error process the response.
     */
    byte[] unwrapResponseAPDU(final byte cmdId, final byte[] apdu, final int packetSize)
            throws IOException {
        final ByteArrayOutputStream response = new ByteArrayOutputStream();
        int offset = 0;

        // channel identifier - 4 bytes [0-3]
        int readChannel = getChannel(apdu, offset);
        if (readChannel != channel) {
            if (channel == CHANNEL_BROADCAST) {
                channel = readChannel;
            } else {
                throw new IOException(
                        "Channel changed during transaction, " + channel + " to " + readChannel +
                                ".");
            }
        }
        offset += CHANNEL_LENGTH;

        // command - byte [4]
        final byte cmd = apdu[offset++];

        if (cmd != cmdId) {
            //throw new IOException("U2FHelper.unwrapResponseAPDU: Invalid command: "+(cmd&0xff)
            // +" Tag: "+(tag&0xff));
            Log.w(TAG, "Command mismatch: " + (cmd & 0xff) + " Tag: " + (cmdId & 0xff));
        }

        // get response length
        int payloadLength = ((apdu[offset++] & 0xff) << 8);
        payloadLength |= (apdu[offset++] & 0xff);

        // check we don't have less data than claimed
        final int firstPacketDataLength = packetSize - FRST_PKT_HDR_LEN;
        int expectedBufferLength;

        if (payloadLength <= firstPacketDataLength) {
            expectedBufferLength = packetSize;
        } else {
            final int continuationPayload = payloadLength - firstPacketDataLength;
            int numPackets = 1 + (continuationPayload / (packetSize - CONT_PACKET_HEADER_LENGTH));
            if (continuationPayload % packetSize != 0) {
                numPackets++;
            }

            expectedBufferLength = packetSize * numPackets;
        }

        if (apdu.length != expectedBufferLength) {
            throw new IOException(
                    "Payload not complete (" + apdu.length + "/" + expectedBufferLength +
                            " bytes).");
        }

        // process each payload block in the buffer

        // process the first packet
        int blockSize = (payloadLength > firstPacketDataLength ? packetSize - FRST_PKT_HDR_LEN :
                payloadLength);
        response.write(apdu, offset, blockSize);
        offset += blockSize;

        // process subsequent packets
        int sequenceCounter = 0;
        while (response.size() != payloadLength) {
            // get the channel
            readChannel = getChannel(apdu, offset);
            offset += CHANNEL_LENGTH;
            if (readChannel != channel) {
                throw new IOException(
                        "Channel changed during transaction: " + channel + " to " + readChannel);
            }

            // get the sequence number
            final byte sequenceId = apdu[offset++];
            if (sequenceId != sequenceCounter) {
                throw new IOException(
                        "Out of sequence packet. Sequence " + sequenceId + "; expected " +
                                sequenceCounter);
            }
            final int contPacketDataLength = packetSize - CONT_PACKET_HEADER_LENGTH;
            blockSize = (payloadLength - response.size() > contPacketDataLength ?
                    contPacketDataLength : payloadLength - response.size());

            response.write(apdu, offset, blockSize);
            offset += blockSize;
            sequenceCounter++;
        }

        return response.toByteArray();
    }
}
