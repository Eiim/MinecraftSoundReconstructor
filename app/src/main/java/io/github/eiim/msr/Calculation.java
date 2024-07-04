package io.github.eiim.msr;

import java.util.ArrayList;
import java.util.List;

import io.github.eiim.msr.Main.SoundData;
import io.github.eiim.msr.MojangClient.SoundAsset;

public class Calculation {

	public static AppxResult approximate(List<SoundAsset> assets, SoundData targetData) {
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
				ArrayList<SoundAsset> candidates = new ArrayList<>();
				for(SoundAsset asset : assets) {
					if(avail >= asset.data.samples().length) candidates.add(asset);
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
					MultResult res =  correlate(candidates.get(j).data, tsound, i, base);
					if(res.qual() < bestQual) {
						bestQual = res.qual();
						bestIndex = j;
						bestMult = res.mult();
					}
				}

				if(bestQual < Double.MAX_VALUE) {
					for(int j = 0; j < candidates.get(bestIndex).data.samples().length; j++) {
						for(int k = 0; k < 2400; k++) {
							tsound[i+j][k] -= (bestMult * candidates.get(bestIndex).data.samples()[j][k]);
							newsound[i+j][k] -= (bestMult * candidates.get(bestIndex).data.samples()[j][k]);
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
		return new AppxResult(result);
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
    
    private record MultResult(double mult, double qual) { }
    public record AppxResult(short[][] sound) {}
}
