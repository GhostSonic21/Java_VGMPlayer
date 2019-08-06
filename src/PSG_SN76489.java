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

// Emulator for the SN76489

public class PSG_SN76489 {
    // In-line classes because I don't want to put this in its own class file

    private interface SoundChannel{
        void clock();
        int getOutputVol();
        void writeData(boolean dataOrVolume, boolean wide, int data);
    }

    private class SquareWave implements SoundChannel{
        private int volume = 0xF;
        private int resetValue = 0;
        private int resetValueReload = 0;
        private boolean lowOrHighOutput = false;    // Is output low or high (??)

        @Override
        public void clock(){
            if (--resetValue <= 0){
                lowOrHighOutput = !lowOrHighOutput;
                resetValue = resetValueReload;
            }
        }

        @Override
        public int getOutputVol(){
            if (!lowOrHighOutput){
                return 0xF;
            }
            else{
                //return 0xF - volume;
                return volume;
            }
        }

        @Override
        public void writeData(boolean dataOrVolume, boolean wide, int data){
            // dataOrVolume = is this a reset value write or a volume write
            // wide = 6-bit value or 4-bit value?
            // Data, self explanatory
            if(dataOrVolume){
                volume = data & 0xF;
            }
            else{
                if (wide){
                    resetValueReload = (resetValueReload & 0xF) | ((data & 0x3F) << 4);
                    //resetValue = resetValueReload;
                }
                else{
                    resetValueReload = (resetValueReload & 0x3F0) | (data & 0xF);
                    //resetValue = resetValueReload;
                }
            }
        }

        public int getResetValueReload(){
            return resetValueReload;
        }
    }

    private class NoiseGenerator implements SoundChannel{
        private int volume = 0xF;
        private int resetValue = 0x10;
        private int resetValueReload = 0;
        private boolean lowOrHighOutput = false;
        private boolean outputToggle = false;   // So this is like the low/high on square, but it shifts the lsfr??
        private int LSFR = 0x8000;
        private boolean whiteNoise = false;
        // Needed since channel 2 and noise can share a frequency
        private SquareWave channel2;
        private int[] resetValueTable = {0x10,0x20,0x40};

        public NoiseGenerator(SquareWave channel2){
            this.channel2 = channel2;
        }

        @Override
        public void clock() {
            if (--resetValue <= 0){
                outputToggle = !outputToggle;
                if (outputToggle) {
                    // Hopefully this is the right order?
                    lowOrHighOutput = (LSFR & 0x1) == 0x1;
                    // Method of rotation of the LSFR depends on the whiteNoise value
                    // We can use ternary for fun
                    int valueRotated = whiteNoise ? ((LSFR & 0x1) ^ ((LSFR >> 3) & 0x1)) : (LSFR & 0x1);
                    LSFR >>= 1;
                    LSFR |= valueRotated << 15;
                }
                if (resetValueReload == 0x3){
                    resetValue = channel2.getResetValueReload();
                }
                else {
                    resetValue = resetValueTable[resetValueReload];
                }
            }
        }

        @Override
        public int getOutputVol() {
            if (!lowOrHighOutput){
                return 0xF;
            }
            else{
                //return 0xF - volume;
                return volume;
            }
        }

        @Override
        public void writeData(boolean dataOrVolume, boolean wide, int data) {
            if (dataOrVolume){
                volume = data & 0xF;
            }
            else{
                resetValueReload = data & 0x3;
                whiteNoise = (data & 0x4) == 0x4;
                if (!whiteNoise){
                    System.out.printf("Break\n");
                }
            }
        }
    }

    // Actual variables and objects
    private final int clocksReset = 16;
    private int clocks = clocksReset;    // Contains a 16 clock divider (is this right?)
    private SoundChannel[] soundChannels;

    // Latch thing
    private int latchedChannel = 0;
    private boolean latchedChannelVolumeWrite = false;


    // Volume lookup table
    private final int[] volumeTable = {0x1FFF,0x196A,0x1430,0x1009,
            0xCBC,0xA1E,0x809,0x662,
            0x512,0x407,0x333,0x28A,
            0x204,0x19a,0x146,0x000};

    // Constructor
    public PSG_SN76489(){
        // Construct things
        soundChannels = new SoundChannel[4];
        soundChannels[0] = new SquareWave();
        soundChannels[1] = new SquareWave();
        // Hacky solution to the frequency share below
        SquareWave channel2 = new SquareWave();
        soundChannels[2] = channel2;
        soundChannels[3] = new NoiseGenerator(channel2);
    }


    public void registerWrite(int data){
        // There's only one write port, so address is not needed
        // Bit 7 on data decides if it's latch/data byte or data byte
        // Data
        if ((data & 0x80) == 0x80){
            latchedChannel = (data >> 5) & 0x3;
            latchedChannelVolumeWrite = (data & 0x10) == 0x10;
            soundChannels[latchedChannel].writeData(latchedChannelVolumeWrite,false,data & 0xF);
        }
        // Latch
        else{
            soundChannels[latchedChannel].writeData(latchedChannelVolumeWrite, true, data & 0x3F);
        }
    }

    public void step(int cycles){
        while(cycles-- != 0) {
            // Clock the sound channels accordingly
            if (--clocks <= 0) {
                // Clock the things
                soundChannels[0].clock();
                soundChannels[1].clock();
                soundChannels[2].clock();
                soundChannels[3].clock();
                clocks = clocksReset;
            }
        }
    }

    public int getMixedSample(){
        // Add to soundbuffer, output when full
        // Float code
        /*soundBuffer[soundBufferCount] = (float)((((double) soundChannels[0].getOutputVol() / 100)* 1.67)
                + (((double) soundChannels[1].getOutputVol() / 100)* 1.67)
                + (((double) soundChannels[2].getOutputVol() / 100)* 1.67)
                + (((double) soundChannels[3].getOutputVol() / 100) * 1.67));*/
        //soundBuffer[soundBufferCount] = (float)soundChannels[3].getOutputVol() / 100;
        return (volumeTable[soundChannels[0].getOutputVol()] +
                volumeTable[soundChannels[1].getOutputVol()] +
                volumeTable[soundChannels[2].getOutputVol()] +
                volumeTable[soundChannels[3].getOutputVol()]);
    }

}
