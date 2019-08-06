/*
    Copyright (C) 2019  GhostSonic

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see [http://www.gnu.org/licenses/].
 */

// Main Class

import java.io.*;
import java.util.Random;
import java.util.zip.GZIPInputStream;


public class VGMConverter {
    // Loads VGM data to an array for fast random access
    // Might change this to just having the VGM InputStream parsed directly

    private static int[] loadVGM(String vgmPath) throws IOException{
        int[] vgmData;
        // Open a random access file input
        RandomAccessFile vgmFile = new RandomAccessFile(vgmPath, "r");
        // Detect whether it's a VGM, GZip, or Invalid
        // ReadInt is conveniently big-endian
        int magicNumber = vgmFile.readInt();

        // Copy into vgmData array if it's uncompressed VGM format
        if(magicNumber == 0x56676d20){
            vgmFile.seek(0);
            vgmData = new int[(int)vgmFile.length()];
            for(int i = 0; i < vgmData.length; i++){
                vgmData[i] = vgmFile.read();
            }
            vgmFile.close();
        }

        // If it's a Gzip, decompress it to the vgmData array
        else if (((magicNumber >> 16) & 0xFFFF) == 0X1F8B){
            vgmFile.close();
            GZIPInputStream vgmFileGzip = new GZIPInputStream(new FileInputStream(vgmPath));
            // Need to use the VGM Header to determine the file size, since determining GZIP size can be inconsistent
            byte[] headerbuf = new byte[8];
            vgmFileGzip.read(headerbuf, 0, 8);
            // Check for VGM header
            if(headerbuf[0] == 'V' && headerbuf[1] == 'g' && headerbuf[2] == 'm' && headerbuf[3] == ' '){
                // Read the EOF offset to get a file length, EOF is total length - 4
                int vgmFileLength = ((headerbuf[4] & 0xFF)|((headerbuf[5] & 0xFF) << 8)|
                                    ((headerbuf[6] & 0xFF) << 16)|((headerbuf[7] & 0xFF) << 24)) + 4;
                // Create the array, then copy of the header data for completeness sake
                vgmData = new int[vgmFileLength];
                for(int i = 0; i < 8; i++){
                    vgmData[i] = headerbuf[i] & 0xFF;
                }
                // Copy the rest of the data over
                for (int i = 8; i < vgmFileLength; i++){
                    vgmData[i] = vgmFileGzip.read();
                }
            }
            else{
                vgmData = null;
            }
            vgmFileGzip.close();
        }

        // If it's neither, return null
        else{
            vgmData = null;
        }
        return vgmData;
    }

    private static void savePCMRaw(String vgmPath, int[] pcmSamples) throws IOException{
        // Make a new path
        // Remove existing extension, replace with raw
        String rawPath = vgmPath.substring(0, vgmPath.lastIndexOf('.')) + ".bin";
        // Open output stream
        FileOutputStream outputStream = new FileOutputStream(rawPath);
        // Just write them in little-endian, 16-bit format
        for(int i = 0; i < pcmSamples.length; i++){
            outputStream.write(pcmSamples[i] & 0xFF);
            outputStream.write((pcmSamples[i] >> 8) & 0xFF);
        }
        // Close
        outputStream.close();
    }

    private static void savePCMWav(String vgmPath, int[] pcmSamples) throws IOException{
        // Remove existing extension, replace with raw
        String outputPath = vgmPath.substring(0, vgmPath.lastIndexOf('.')) + ".wav";
        // Open output stream
        FileOutputStream outputStream = new FileOutputStream(outputPath);

        // Add in the header
        int dataSize = pcmSamples.length * 2;
        int chunkSize = dataSize + 36;
        byte[] header = {
                'R','I','F','F', (byte)(chunkSize & 0xFF), (byte)((chunkSize >> 8) & 0xFF),
                (byte)((chunkSize >> 16) & 0xFF), (byte)((chunkSize >> 24) & 0xFF),
                'W','A','V','E',
                'f','m','t',' ',
                16,0,0,0,                       // 16-bit samples
                1,0,                            // PCM Format
                1,0,                            // Mono
                0x44,(byte)0xAC,00,00,          // 44100hz Sample rate
                (byte)0x88,(byte)0x58,0x1,0,    // Byte rate is double sample rate, since 2 bytes per sample
                2,0,                            // 2 Bytes per sample
                16,0,                           // 16 bits per sample
                'd','a','t','a',
                (byte)(dataSize & 0xFF), (byte)((dataSize >> 8) & 0xFF),
                (byte)((dataSize >> 16) & 0xFF), (byte)((dataSize >> 24) & 0xFF)    // Rest of the chunk size
        };

        outputStream.write(header);
        // Just write them in little-endian, 16-bit format
        byte[] writeBuffer = new byte[dataSize];
        for(int i = 0; i < pcmSamples.length; i++){
            writeBuffer[(i*2)]   = (byte)(pcmSamples[i] & 0xFF);
            writeBuffer[(i*2)+1] = (byte)((pcmSamples[i] >> 8) & 0xFF);
        }
        outputStream.write(writeBuffer);
        // Close
        outputStream.close();
        System.out.printf("Saved as %s\n", outputPath);
    }

    private static int shortIntRead(int[] inputArray, int offset){
        return (inputArray[offset])|(inputArray[offset+1] << 8);
    }

    private static int wordIntRead(int[] inputArray, int offset){
        return (inputArray[offset])|(inputArray[offset+1] << 8)|
                (inputArray[offset+2] << 16)|(inputArray[offset+3] << 24);
    }

    private static void fillSamples(PSG_SN76489 psgObj, int[] pcmSamples, int samplePosition, int samples, int cyclesPerSample){
        for(int i = 0; i < samples; i++){
            psgObj.step(cyclesPerSample);
            pcmSamples[samplePosition+i] = psgObj.getMixedSample();
        }
    }

    // Main executor
    public static void main(String[] args){
        // Requires an argument
        if (args.length != 1){
            System.err.printf("No file detected in arguments");
            System.exit(2); // Invalid Argument
        }

        // Open the VGM file into an array
        int[] vgmData = null;
        try {
            vgmData = loadVGM(args[0]);
        } catch (IOException e) {
            e.printStackTrace();
        }

        // Exit if it does not appear to be a valid vgm file
        if (vgmData == null){
            System.err.println("Invalid VGM File or no file detected");
            System.exit(3); // Invalid VGM
        }


        // Get PSG clock, exit if 0
        //int psgClock = (vgmData[0x0C])|(vgmData[0x0D] << 8)|(vgmData[0x0E] << 16)|(vgmData[0x0F] << 24);
        int psgClock = wordIntRead(vgmData, 0x0C);
        if (psgClock == 0) {
            System.err.println("PSG Clock not defined");
            System.exit(4); // PSG not present
        }

        // Sample count, data offset (Absolute)
        //int sampleCount = vgmData[0x18];
        int sampleCount = wordIntRead(vgmData, 0x18);
        int dataOffset = wordIntRead(vgmData, 0x34) + 0x34;
        // If offset was 0, we just need to define a set position
        if (dataOffset == 0x34){
            dataOffset = 0x40;
        }

        // Calculate PSG cycles per sample using simple division (nearest neighbor downsampling, no filter)
        // VGM Specs define sample rate of 44100hz, SN76489 is usually consistent but defined in the header regardless
        int cyclesPerSample = psgClock/44100;

        // Start conversion
        // Relevant VGM Commands:
        // 0x50 Writes nn to the single SN76489 register
        // 0x61 Wait nn nn samples
        // 0x62 Wait 735 samples
        // 0x63 Wait 882 samples
        // 0x66 End of sound data
        // 0x7n wait n+1 samples (Wonder if anything uses this one)
        // Everything else should throw an error/exception/exit/whatever

        // Create a PSG object
        PSG_SN76489 mainPSG = new PSG_SN76489();
        // Create an array for PCM samples
        int[] pcmSamples = new int[sampleCount];
        int samplePosition = 0;
        int vgmPosition = dataOffset;
        while (samplePosition < sampleCount){
            switch(vgmData[vgmPosition]){
                case 0x50:
                    vgmPosition += 1;
                    mainPSG.registerWrite(vgmData[vgmPosition]);
                    vgmPosition += 1;
                    break;
                case 0x61:
                    vgmPosition += 1;
                    int numberOfSamples = shortIntRead(vgmData, vgmPosition);
                    fillSamples(mainPSG, pcmSamples, samplePosition, numberOfSamples, cyclesPerSample);
                    samplePosition += numberOfSamples;
                    vgmPosition += 2;
                    break;
                case 0x62:
                    fillSamples(mainPSG, pcmSamples, samplePosition, 735, cyclesPerSample);
                    samplePosition += 735;
                    vgmPosition += 1;
                    break;
                case 0x63:
                    fillSamples(mainPSG, pcmSamples, samplePosition, 882, cyclesPerSample);
                    samplePosition += 882;
                    vgmPosition += 1;
                    break;
                case 0x66:
                    // Hack?
                    samplePosition = sampleCount;
                    break;
                case 0x70:
                case 0x71:
                case 0x72:
                case 0x73:
                case 0x74:
                case 0x75:
                case 0x76:
                case 0x77:
                case 0x78:
                case 0x79:
                    numberOfSamples = (vgmData[vgmPosition] & 0xF) + 1;
                    fillSamples(mainPSG, pcmSamples, samplePosition, numberOfSamples, cyclesPerSample);
                    samplePosition += numberOfSamples;
                    vgmPosition += 1;
                    break;
                default:
                    System.err.printf("Unimplemented VGM Command 0x%x\n", vgmData[vgmPosition]);
                    System.exit(5);
                    break;

            }
        }
        // Save the PCM buffer to a file
        try {
            savePCMWav(args[0], pcmSamples);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}