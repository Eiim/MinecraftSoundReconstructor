package io.github.eiim.msr;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

public class MojangClient {
	private static final String userAgent = "MinecraftSoundReconstructor/0.1";
	private static final String manifestURL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
	private static final String resourceURL = "https://resources.download.minecraft.net/";
	private HttpClient client;
	
	public MojangClient() {
		client = HttpClient.newBuilder()
    	        .followRedirects(HttpClient.Redirect.NORMAL)
    	        .connectTimeout(Duration.ofSeconds(30))
    	        .build();
	}
	
	public Version[] getVersions() {
		try {
			HttpRequest request = HttpRequest.newBuilder(new URI(manifestURL))
											.setHeader("User-Agent", userAgent)
											.build();
			HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
			JSONArray vdata = JSON.parseObject(response.body()).getJSONArray("versions");
			Version[] versions = new Version[vdata.size()];
			for(int i = 0; i < vdata.size(); i++) {
				JSONObject v = vdata.getJSONObject(i);
				versions[i] = new Version(v.getString("id"), v.getString("url"));
			}
			return versions;
		} catch(Exception e) {
			System.out.println("Uhh... that shouldn't have happened");
			System.out.println(e.getMessage());
			e.printStackTrace();
			return null;
		}
	}
	
	public List<SoundAsset> getSounds(String packageURL, File objectsDir) {
		try {
			HttpRequest request = HttpRequest.newBuilder(new URI(packageURL))
					.setHeader("User-Agent", userAgent)
					.build();
			HttpResponse<String> response = client.send(request, BodyHandlers.ofString());
			String indexURL = JSON.parseObject(response.body()).getJSONObject("assetIndex").getString("url");
			
			HttpRequest request2 = HttpRequest.newBuilder(new URI(indexURL))
					.setHeader("User-Agent", userAgent)
					.build();
			HttpResponse<String> response2 = client.send(request2, BodyHandlers.ofString());
			JSONObject assets = JSON.parseObject(response2.body()).getJSONObject("objects");
			List<SoundAsset> sounds = new ArrayList<SoundAsset>();			
			System.out.println("Downloading sound files (this will take a while on first run)");
			for(String key : assets.keySet()) {
				if(key.endsWith(".ogg")) {
					String hash = assets.getJSONObject(key).getString("hash");
					File loc = new File(objectsDir, hash.substring(0,2)+"/"+hash);
					sounds.add(new SoundAsset(loc, hash));
					if(!loc.isFile()) {
						loc.getParentFile().mkdirs();
						loc.createNewFile();
						HttpRequest request3 = HttpRequest.newBuilder(new URI(resourceURL+hash.substring(0,2)+"/"+hash))
								.setHeader("User-Agent", userAgent)
								.build();
						client.send(request3, BodyHandlers.ofFile(loc.toPath()));
					}
				}
			}
			
			return sounds;
		} catch(Exception e) {
			System.out.println("Something went wrong getting the sounds.");
			System.out.println(e.getMessage());
			e.printStackTrace();
		}

		return null;
	}
	
	public record Version(String version, String packageURL) { }
	public record SoundAsset(File path, String hash) { }
}
