package io.github.eiim.msr;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
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
import io.github.eiim.msr.MojangClient.SoundAsset;
import io.github.eiim.msr.MojangClient.Version;

public class Main {
    public static void main(String[] args) {
    	MojangClient mc = new MojangClient();
        Version[] versions = mc.getVersions();
        Scanner input = new Scanner(System.in);
        System.out.print("Minecraft version: ");
        String mcver = input.nextLine();
        for(Version v : versions) {
        	if(mcver.equals(v.version())) {
        		File objectsDir = getObjectsDir();
        		List<SoundAsset> assets = mc.getSounds(v.packageURL(), objectsDir);
        		System.out.print("Target audio file: ");
        		String target = input.nextLine();
        		SoundData result = approximate(assets, target);
        		writeSoundFile(new File("mcappx.wav"), result);
        		break;
        	}
        }
        
        input.close();
    }
    
    private static SoundData approximate(List<SoundAsset> assets, String target) {
    	System.out.println("Loading target audio file...");
    	SoundData targetData = loadTarget(target);
    	
    	System.out.println("Loading sound files (this will take a while)...");
		long start = System.currentTimeMillis();
    	ArrayList<SoundData> mcSounds = new ArrayList<>();
    	for(SoundAsset asset : assets) {
			//System.out.println("Loading "+asset.hash());
    		mcSounds.add(loadAsset(asset.path()));
    	}
		long duration = System.currentTimeMillis() - start;

		int totalTicks = 0;
		for(SoundData sound : mcSounds) {
			totalTicks += sound.samples().length;
		}
		System.out.println("Read "+(totalTicks/20)+" seconds of audio data in "+(duration/1000)+" seconds (Ratio: "+((totalTicks/20.0)/(duration/1000))+")");
    	System.out.println(mcSounds.size()+" sound files loaded");

		ArrayList<Integer> onsets = new ArrayList<>();
		ArrayList<Double> multipliers = new ArrayList<>();
		ArrayList<SoundAsset> sounds = new ArrayList<>();
		int[] active = new int[targetData.samples().length];
		float[][] tsound = new float[targetData.samples().length][2400];
		float[][] newsound = new float[targetData.samples().length][2400];
		for(int i = 0; i < targetData.samples().length; i++) {
			for(int j = 0; j < 2400; j++) {
				tsound[i][j] = targetData.samples()[i][j];
			}
		}
		
		boolean flag = true;
		int iter = 0;
		while(flag) {
			flag = false;
			iter++;
			long total = 0;
			int count = 0;
			for(int i = 0; i < tsound.length; i++) {
				for(int j = 0; j < tsound[i].length; j++) {
					total += Math.abs(tsound[i][j]);
				}
				count += tsound[i].length;
			}
			System.out.println("Iteration "+iter+" MAE: "+(total/(double)count));

			for(int i = 0; i < tsound.length; i++) {
				// How many slots aren't filled up yet?
				int avail = 0;
				while(i+avail < tsound.length && active[i+avail] < 247) avail++;
				// Only use short enough sounds
				ArrayList<SoundData> candidates = new ArrayList<>();
				for(SoundData sound : mcSounds) {
					if(avail >= sound.samples().length) candidates.add(sound);
				}
				if(candidates.size() == 0) continue;
				double bestQual = Double.MAX_VALUE;
				int bestIndex = -1;
				double bestMult = 0;

				double[] base = new double[tsound.length];
				for(int j = 0; j < tsound.length; j++) {
					for(int k = 0; k < 2400; k++) {
						base[j] += tsound[j][k] * tsound[j][k];
					}
				}

				for(int j = 0; j < candidates.size(); j++) {
					MultResult res =  correlate(candidates.get(j), tsound, i, base);
					if(res.qual() < bestQual) {
						bestQual = res.qual();
						bestIndex = j;
						bestMult = res.mult();
					}
				}

				if(bestQual < Double.MAX_VALUE) {
					for(int j = 0; j < candidates.get(bestIndex).samples.length; j++) {
						for(int k = 0; k < 2400; k++) {
							tsound[i+j][k] -= (bestMult * candidates.get(bestIndex).samples[j][k]);
							newsound[i+j][k] -= (bestMult * candidates.get(bestIndex).samples[j][k]);
						}
						active[i+j]++;
					}
					onsets.add(i);
					multipliers.add(bestMult);
					sounds.add(assets.get(bestIndex));
					flag = true;
				}
			}
		}
    	
		short[][] result = new short[newsound.length][2400];
		for(int i = 0; i < newsound.length; i++) {
			for(int j = 0; j < 2400; j++) {
				result[i][j] = (short)newsound[i][j];
			}
		}
		writeSoundFile(new File("appx-"+target), new SoundData(target, result));
    	return new SoundData(target, result);
    }
    
    private static MultResult correlate(SoundData sound, float[][] target, int offset, double[] base) {
		double soundsq = 0;
		double cross = 0;
		double basePart = 0;
		for(int i = 0; i < sound.samples().length; i++) {
			for(int j = 0; j < 2400; j++) {
				cross += target[i+offset][j] * sound.samples()[i][j];
			}
			soundsq += sound.squares()[i];
			basePart += base[i+offset];
		}
		double mult = cross / soundsq;
		if(mult < 0) return new MultResult(0, Double.MAX_VALUE);
		double resid = 0;
		double residPart = 0;
		for(int i = 0; i < sound.samples().length; i++) {
			for(int j = 0; j < 2400; j++) {
				residPart = target[i+offset][j] - mult * sound.samples()[i][j];
				resid += residPart * residPart;
			}
		}
		return new MultResult(mult, resid / basePart);
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
    		
    		System.out.println(samples.size());
    		
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
	
	private record MultResult(double mult, double qual) { }
}
