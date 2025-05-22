package io.github.eiim.msr;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.eiim.msr.Main.SoundData;
import io.github.eiim.msr.MojangClient.SoundAsset;

public class Calculation {

	public static AppxResult approximate(List<SoundAsset> assets, SoundData targetData, int threads) {
		ArrayList<Integer> onsets = new ArrayList<>();
		ArrayList<Double> multipliers = new ArrayList<>();
		ArrayList<SoundAsset> sounds = new ArrayList<>();
		int[] active = new int[targetData.samples().length];
		float[][] tsound = new float[targetData.samples().length][2400];
		float[][] newsound = new float[targetData.samples().length][2400];
		// convert target sound to float for precision
		for(int i = 0; i < targetData.samples().length; i++) {
			for(int j = 0; j < 2400; j++) {
				tsound[i][j] = targetData.samples()[i][j];
			}
		}
		
		// Create thread pool
		ExecutorService threadPool = Executors.newFixedThreadPool(threads);
		
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
				ArrayList<SoundAsset> candidates = new ArrayList<>();
				for(SoundAsset asset : assets) {
					if(avail >= asset.data.samples().length) candidates.add(asset);
				}
				int canSize = candidates.size();
				if(canSize == 0) continue;
				double bestQual = Double.MAX_VALUE;
			    SoundAsset bestAsset = null;
				double bestMult = 0;

				double[] base = new double[tsound.length];
				for(int j = 0; j < tsound.length; j++) {
					for(int k = 0; k < 2400; k++) {
						base[j] += tsound[j][k] * tsound[j][k];
					}
				}
				
				Queue<CorrParams> corrQueue = new LinkedList<>();
				Queue<MultResult> resultQueue = new LinkedList<>();
				
				for(int j = 0; j < candidates.size(); j++) {
					corrQueue.add(new CorrParams(candidates.get(j), tsound, i, base));
				}
				
				while (resultQueue.size() < canSize) {
					try {
						Thread.sleep(500);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
				
				synchronized(resultQueue) {
					for (MultResult res : resultQueue) {
						if (res.qual() < bestQual) {
							bestQual = res.qual();
							bestAsset = res.asset();
							bestMult = res.mult();
						}
					}
				}

				if(bestQual < Double.MAX_VALUE) {
					for(int j = 0; j < bestAsset.data.samples().length; j++) {
						for(int k = 0; k < 2400; k++) {
							/*try {
								tsound[i+j][k] -= (bestMult * bestAsset.data.samples()[j][k]);
								newsound[i+j][k] += (bestMult * bestAsset.data.samples()[j][k]);
							} catch (ArrayIndexOutOfBoundsException e) {
								System.out.println("Array index out of bounds");
								System.out.println("i: " + i + ", j: " + j + ", k: " + k);
								System.out.println("avail: " + avail);
								System.out.println("tsound: " + tsound.length);
								System.out.println("newsound: " + newsound.length);
								System.out.println("bestAsset: " + bestAsset.data.samples().length);
							}*/
							tsound[i+j][k] -= (bestMult * bestAsset.data.samples()[j][k]);
							newsound[i+j][k] -= (bestMult * bestAsset.data.samples()[j][k]);
						}
						active[i+j]++;
					}
					onsets.add(i);
					multipliers.add(bestMult);
					sounds.add(bestAsset);
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
		return new AppxResult(result);
    }
    
    private static MultResult correlate(CorrParams params) {
    	
		double soundsq = 0;
		double cross = 0;
		double basePart = 0;
		for(int i = 0; i < params.asset.data.samples().length; i++) {
			for(int j = 0; j < 2400; j++) {
				cross += params.target[i+params.offset][j] * params.asset.data.samples()[i][j];
			}
			soundsq += params.asset.data.squares()[i];
			basePart += params.base[i+params.offset];
		}
		double mult = cross / soundsq;
		if(mult < 0) return new MultResult(params.asset, 0, Double.MAX_VALUE);
		double resid = 0;
		double residPart = 0;
		for(int i = 0; i < params.asset.data.samples().length; i++) {
			for(int j = 0; j < 2400; j++) {
				residPart = params.target[i+params.offset][j] - mult * params.asset.data.samples()[i][j];
				resid += residPart * residPart;
			}
		}
		return new MultResult(params.asset, mult, resid / basePart);
	}
    
    private record CorrParams(SoundAsset asset, float[][] target, int offset, double[] base) { }
    private record MultResult(SoundAsset asset, double mult, double qual) { }
    public record AppxResult(short[][] sound) {}
}
