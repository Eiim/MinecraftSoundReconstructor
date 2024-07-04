package io.github.eiim.msr;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Scanner;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import de.jarnbjo.ogg.BasicStream;
import de.jarnbjo.ogg.EndOfOggStreamException;
import de.jarnbjo.ogg.LogicalOggStream;
import de.jarnbjo.vorbis.VorbisStream;
import io.github.eiim.msr.Calculation.AppxResult;
import io.github.eiim.msr.MojangClient.SoundAsset;
import io.github.eiim.msr.MojangClient.Version;

public class Main {
    public static void main(String[] args) {
    	MojangClient mc = new MojangClient();
        Version[] versions = mc.getVersions();
        Scanner input = new Scanner(System.in);
        System.out.print("Threads: ");
        int threads = input.nextInt();
        input.nextLine();
        System.out.print("Minecraft version: ");
        String mcver = input.nextLine();
        for(Version v : versions) {
        	if(mcver.equals(v.version())) {
        		File objectsDir = getObjectsDir();
        		List<SoundAsset> assets = mc.getSounds(v.packageURL(), objectsDir);
        		System.out.print("Target audio file: ");
        		String target = input.nextLine();
        		SoundData result = getApproximation(assets, target, threads);
        		writeSoundFile(new File("mcappx.wav"), result);
        		break;
        	}
        }
        
        input.close();
    }
    
    private static SoundData getApproximation(List<SoundAsset> assets, String target, int threads) {
    	System.out.println("Loading target audio file...");
    	SoundData targetData = Main.loadTarget(target);
    	
    	System.out.println("Loading sound files (this will take a while)...");
		long start = System.currentTimeMillis();
    	Queue<SoundAsset> assetQueue = new LinkedList<>(assets);
    	Thread[] threadPool = new Thread[threads];
    	for(int i = 0; i < threads; i++) {
			Thread t = new Thread(() -> {
				while (!assetQueue.isEmpty()) {
					SoundAsset asset = null;
					synchronized (assetQueue) {
						asset = assetQueue.poll();
						if(asset == null) break;
					}
					// System.out.println("Loading "+asset.hash());
					asset.data = loadAsset(asset.path);
				}
			});
			t.start();
			threadPool[i] = t;
    	}
    	for(Thread t : threadPool) {
    		try {
    			t.join();
    		} catch(InterruptedException e) {
    			e.printStackTrace();
    		}
    	}
		long duration = System.currentTimeMillis() - start;

		int totalTicks = 0;
		for(SoundAsset asset : assets) {
			totalTicks += asset.data.samples().length;
		}
		System.out.println("Read "+(totalTicks/20)+" seconds of audio data in "+(duration/1000)+" seconds (Ratio: "+((totalTicks/20.0)/(duration/1000))+")");
    	System.out.println(assets.size()+" sound files loaded");
    	AppxResult res = Calculation.approximate(assets, targetData);
    	writeSoundFile(new File("appx-"+target), new SoundData(target, res.sound()));
    	return new SoundData(target, res.sound());
    }

	private static SoundData loadTarget(String targetName) {
    	File targetFile = new File(targetName);
    	if(!targetFile.isFile()) {
    		System.out.println("Provided audio file doesn't exist!");
    	}
    	try {
    		// 48 kHz, 16-bit samples, mono, signed, big endian
    		// Chosen to mimic Minecraft's native format
    		AudioFormat targetaf = new AudioFormat(48000, 16, 1, true, true);
    		AudioInputStream rawstream = AudioSystem.getAudioInputStream(targetFile);
    		AudioInputStream stream = AudioSystem.getAudioInputStream(targetaf, rawstream);
    		
    		int numBytes = 2*2400;
    		byte[] bytes = new byte[numBytes];
    		int bytesRead = 0;
    		ArrayList<short[]> samples = new ArrayList<short[]>();
    		while((bytesRead = stream.read(bytes)) != -1) {
    			if(bytesRead != numBytes) {
    				for(int i = bytesRead; i < numBytes; i++) {
    					bytes[i] = 0;
    				}
    			}
    			short[] sample = new short[2400];
    			for(int i = 0; i < 2400; i++) {
    				short v = (short)(((bytes[2*i] & 0xFF) << 8) | (bytes[2*i+1] & 0xFF));
    				sample[i] = v;
    			}
    			samples.add(sample);
    		}
    		
    		stream.close();
    		
    		System.out.println(samples.size()+" Ticks in the target audio file");
    		
    		return new SoundData(targetName, samples.toArray(new short[][] {}));
    	} catch (IOException | UnsupportedAudioFileException e) {
    		System.out.println("Couldn't read the audio file as a valid audio file");
    		e.printStackTrace();
    		return null;
    	}
    }
    
    private static File getObjectsDir() {
    	File objectsDir = new File("objects");
		if(System.getProperty("os.name").startsWith("Windows")) {
			File mcObjects = new File(System.getenv("APPDATA")+"\\.minecraft\\assets\\objects");
			if(mcObjects.isDirectory()) {
				System.out.println("Using Minecraft objects directory");
				objectsDir = mcObjects;
			} else {
				System.out.println("Couldn't find Minecraft objects directory, defaulting to current directory");
			}
		} else {
			System.out.println("Don't know how to find Minecraft objects directory on your system");
		}
		objectsDir.mkdirs();
		return objectsDir;
    }
    
    private static SoundData loadAsset(File assetFile) {
    	ArrayList<short[]> samples = new ArrayList<>();
    	short[] shortSamples = new short[2400];
    	
    	try {
			BasicStream stream = new BasicStream(new FileInputStream(assetFile));
			LogicalOggStream lstream = (LogicalOggStream) stream.getLogicalStreams().iterator().next();
			VorbisStream vstream = new VorbisStream(lstream);
			
			boolean isDone = false;
			while(!isDone) {
				byte[] sampleArr = new byte[4800];
				int read = 0;
				try {
					while(read < 4800) {
						//System.out.println("Reading at "+new Date().toString());
						int tempRead = vstream.readPcm(sampleArr, read, 4800-read);
						//System.out.println("Read "+tempRead+"/"+(read+tempRead)+" bytes at "+new Date().toString());
						if(tempRead == 0) {
							break;
						}
						read += tempRead;
					}
				} catch (EndOfOggStreamException e) {
					isDone = true;
				}
				for(int i = 0; i+1 < read; i += 2) {
					short v = (short)(((sampleArr[i] & 0xFF) << 8) | (sampleArr[i+1] & 0xFF));
					shortSamples[i/2] = v;
				}
				samples.add(shortSamples);
				shortSamples = new short[2400];
			}
			stream.close();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
    	
    	short[][] sampleData = samples.toArray(new short[][] {});
    	
    	return new SoundData(assetFile.getName(), sampleData);
    }
    
    private static void writeSoundFile(File location, SoundData data) {
    	try {
			AudioFormat af = new AudioFormat(48000, 16, 1, true, true);
			byte[] bytes = new byte[data.samples.length * 2400 * 2];
			for(int i = 0; i < data.samples.length; i++) {
				for(int j = 0; j < 2400; j++) {
					short v = data.samples[i][j];
					bytes[4800*i + 2*j] = (byte) (v >> 8);
					bytes[4800*i + 2*j + 1] = (byte)v;
				}
			}
			AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(bytes), af, (int)Math.ceil(bytes.length / af.getFrameSize()));
			AudioSystem.write(ais, AudioFileFormat.Type.WAVE, location);
		} catch (IOException e) {
			e.printStackTrace();
		}
    }
    
    //public record SoundData(String name, short[][] samples) { }
	public static final class SoundData {
		private final String name;
		private final short[][] samples;
		private double[] squares = null;
		
		public SoundData(String name, short[][] samples) {
			this.name = name;
			this.samples = samples;
		}
		
		public String name() {
			return name;
		}
		
		public short[][] samples() {
			return samples;
		}

		public double[] squares() {
			if(squares != null) return squares;
			squares = new double[samples.length];
			for(int i = 0; i < samples.length; i++) {
				for(int j = 0; j < 2400; j++) {
					squares[i] += samples[i][j] * samples[i][j];
				}
			}
			return squares;
		}
	}
}
