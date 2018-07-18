package tyt.fabric.manager;

import org.yaml.snakeyaml.Yaml;

import tyt.fabric.source.SampleOrg;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.*;

/**
 * init config
 */
public class InitConfig {
    private long waiteTime = 1000000;
    private static final Properties sdkProperties = new Properties();
    private static final HashMap<String, SampleOrg> sampleOrgs = new HashMap<>();

    private static InitConfig initConfig;
    private HashMap configMap;

    public static String orgName = null;
    public static String CHANNEL_NAME = null;
    public static String CHAIN_CODE_NAME = null;
    public static String CHAIN_CODE_VERSION = null;
    
    public static String TEST_ADMIN_NAME = "Admin";
    public static String TESTUSER_1_NAME = "User1";
    static String sslProvider = "openSSL";
    static String negotiationType = "TLS";

    private InitConfig(String configPath,String UserName) {   
//    	System.getProperty("user.dir") + 
        File configFile = new File(configPath);
        System.out.printf("configFile : %s\n", configFile.getAbsolutePath());
        Yaml yaml = new Yaml();
        //HashMap configMap = null;
        try {

            configMap = (HashMap) yaml.load(new FileInputStream(configFile));

            // System.out.printf("configMap : %s\n",configMap);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        setSampleOrg(UserName);
    }

    private void setSampleOrg(String UserName) {
    	//获取组织名
    	orgName = (String) ((HashMap) configMap.get("client")).get("organization");
    	System.out.println("Organization:"+orgName);
    	
    	//获取通道、链码及其版本
    	for (String str: (Set<String>)((HashMap) configMap.get("channels")).keySet())	{
    		CHANNEL_NAME = str;
    		for (String s:((ArrayList<String>)((HashMap)((HashMap) configMap.get("channels")).get(str)).get("chaincodes")))	{
    			String[] slist = s.split(":");
    			CHAIN_CODE_NAME = slist[0];
    			CHAIN_CODE_VERSION = slist[1];
    			break;
    		}
    		break;
    	}
    	System.out.println("Channel Name:"+CHANNEL_NAME);
    	System.out.println("Chain Code Name:"+CHAIN_CODE_NAME);
    	System.out.println("Chain Code Version:"+CHAIN_CODE_VERSION);
    	
    	
        HashMap orgMap = (HashMap) configMap.get("organizations");
        HashMap peersMap = (HashMap) configMap.get("peers");
        HashMap orderersMap = (HashMap) configMap.get("orderers");

        for (Object object : orgMap.entrySet()) {
            Map.Entry eachOrgMap = (Map.Entry) object;
            HashMap eachOrgMapValue = (HashMap) eachOrgMap.getValue();
            SampleOrg sampleOrg = new SampleOrg(eachOrgMap.getKey().toString(), eachOrgMapValue.get("mspid").toString());

            String cryptoPath =eachOrgMapValue.get("cryptoPath").toString();
            String clientKeyFile =eachOrgMapValue.get("tlsCryptoKeyPath").toString();
            String clientCertFile =eachOrgMapValue.get("tlsCryptoCertPath").toString();
        	System.out.println("Crypto Path:"+cryptoPath);
        	System.out.println("Client Cert File:"+clientKeyFile);
        	System.out.println("Client Cert File:"+clientCertFile);

            sampleOrg.setKeystorePath(cryptoPath+"/keystore/");
            sampleOrg.setSigncertsPath(cryptoPath+"/signcerts/"+UserName+"@"+orgName+".peer-"+orgName+".default.svc.cluster.local-cert.pem");

            ArrayList orgPeersArrary = (ArrayList) eachOrgMapValue.get("peers");
            for (Object eachPeer : orgPeersArrary) {
                HashMap eachPeerMap = (HashMap) peersMap.get(eachPeer.toString());
            	System.out.println("Peer Name:"+eachPeer.toString());

                sampleOrg.addPeerLocation(eachPeer.toString(), eachPeerMap.get("url").toString());
            	System.out.println("	Peer Location:"+eachPeerMap.get("url").toString());
            	
                sampleOrg.addEventHubLocation(eachPeer.toString(), eachPeerMap.get("eventUrl").toString());
            	System.out.println("	Event Hub Location:"+eachPeerMap.get("eventUrl").toString());

            	
                Properties pro = getProperties(eachPeerMap);

                pro.setProperty("hostnameOverride", eachPeer.toString());
            	System.out.println("	Host Name Override:"+eachPeer.toString());
                
                pro.put("clientKeyFile", clientKeyFile);
            	System.out.println("	Client Key File:"+cryptoPath);
            	
                pro.put("clientCertFile", clientCertFile);
            	System.out.println("	Client Cert File:"+cryptoPath);


                sampleOrg.addPeerProperties(eachPeer.toString(), pro);
            }

            for (Object eachOrderer : orderersMap.entrySet()) {
                Map.Entry eachOrdererEntry = (Map.Entry) eachOrderer;
                HashMap eachOrdererMap = (HashMap) eachOrdererEntry.getValue();
                String url = eachOrdererMap.get("url").toString();
                sampleOrg.addOrdererLocation(eachOrdererEntry.getKey().toString(), url);
            	System.out.println("Orderer Name:"+eachOrdererEntry.getKey().toString());

                Properties pro = getProperties(eachOrdererMap);
                
                pro.setProperty("hostnameOverride", eachOrdererEntry.getKey().toString());
            	System.out.println("	Host Name Override:"+eachOrdererMap.toString());
                //pro.put("clientKeyFile",clientKeyFile);
                //pro.put("clientCertFile",clientCertFile);

                sampleOrg.addOrdererProperties(eachOrdererEntry.getKey().toString(), pro);
            }

            sampleOrgs.put(eachOrgMap.getKey().toString(), sampleOrg);
            
            break;
        }
    }

    private Properties getProperties(HashMap nodeMap) {
        Properties properties = new Properties();
        HashMap grpcMap = (HashMap) nodeMap.get("grpcOptions");
        properties.setProperty("pemFile", ((HashMap) nodeMap.get("tlsCACerts")).get("path").toString());
    	System.out.println("	Pem File:"+((HashMap) nodeMap.get("tlsCACerts")).get("path").toString());
    	
        properties.setProperty("sslProvider", sslProvider);
    	System.out.println("	SSL Provider:"+sslProvider);
    	
        properties.setProperty("negotiationType", negotiationType);
    	System.out.println("	Negotiation Type:"+negotiationType);
        return properties;
    }


    public Properties getSMProperties() {
        Properties properties = new Properties();
        properties.setProperty("org.hyperledger.fabric.sdk.hash_algorithm", "SM3");
        properties.setProperty("org.hyperledger.fabric.sdk.crypto.default_signature_userid", "1234567812345678");
        return properties;
    }


    public void setWaiteTime(long waiteTime) {
        this.waiteTime = waiteTime;
    }

    public static InitConfig getConfig(String configPath,String UserName) {
        if (initConfig == null) {
            initConfig = new InitConfig(configPath,UserName);
        }
        return initConfig;
    }

    public Collection<SampleOrg> getIntegrationSampleOrgs() {
        return Collections.unmodifiableCollection(sampleOrgs.values());
    }

    public SampleOrg getIntegrationSampleOrg(String name) {
        return sampleOrgs.get(name);
    }

    public long getWaiteTime() {
        return waiteTime;
    }

    /*
    public static void main(String[] args) throws FileNotFoundException {
        new InitConfig("/src/main/fixture/config/network-config.yaml");
    }
    */

    private static void setProperty(String key, String value) {
        String ret = System.getProperty(key);
        if (ret != null) {
            sdkProperties.put(key, ret);
        } else {
            String envKey = key.toUpperCase().replaceAll("\\.", "_");
            ret = System.getenv(envKey);
            if (null != ret) {
                sdkProperties.put(key, ret);
            } else {
                if (null == sdkProperties.getProperty(key) && value != null) {
                    sdkProperties.put(key, value);
                }

            }

        }
    }

}
