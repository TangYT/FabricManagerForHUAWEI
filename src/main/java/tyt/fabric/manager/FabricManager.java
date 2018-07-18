package tyt.fabric.manager;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.hyperledger.fabric.sdk.BlockEvent;
import org.hyperledger.fabric.sdk.ChaincodeID;
import org.hyperledger.fabric.sdk.Channel;
import org.hyperledger.fabric.sdk.EventHub;
import org.hyperledger.fabric.sdk.HFClient;
import org.hyperledger.fabric.sdk.Peer;
import org.hyperledger.fabric.sdk.ProposalResponse;
import org.hyperledger.fabric.sdk.QueryByChaincodeRequest;
import org.hyperledger.fabric.sdk.SDKUtils;
import org.hyperledger.fabric.sdk.TransactionProposalRequest;
import org.hyperledger.fabric.sdk.exception.CryptoException;
import org.hyperledger.fabric.sdk.exception.InvalidArgumentException;
import org.hyperledger.fabric.sdk.security.CryptoSuite;

import tyt.fabric.source.SampleOrg;
import tyt.fabric.source.SampleStore;
import tyt.fabric.source.SampleUser;

public class FabricManager {
	private static InitConfig initConfig =null;
    //private static InitConfig initConfig = InitConfig.getConfig("/src/main/resources/fixture/config/hdrNoDelete-sdk-config.sm.yaml");
    //private static TestConfig testConfig = TestConfig.getConfig();

    private HFClient client = null;
    private Channel channel = null;
    private ChaincodeID chaincodeID = null;


    private CryptoSuite initCryptoSuite(String type , String congig_path,String UserName) throws IllegalAccessException, InvocationTargetException, InvalidArgumentException, InstantiationException, NoSuchMethodException, CryptoException, ClassNotFoundException {
        CryptoSuite cs = null;
//        if (type.equals("SW") || type.equals("sw") ||type.equals("")){
//            initConfig = InitConfig.getConfig("/src/main/resources/fixture/config/hdrNoDelete-sdk-config.yaml");
//            cs= CryptoSuite.Factory.getCryptoSuite();
//          }else if (type.equals("sm") || type.equals("SM")){
//            initConfig = InitConfig.getConfig("/src/main/resources/fixture/config/hdrNoDelete-sdk-config.sm.yaml");
//            cs = CryptoSuite.Factory.getCryptoSuite(initConfig.getSMProperties());
//        }
        initConfig = InitConfig.getConfig(congig_path,UserName);
        cs= CryptoSuite.Factory.getCryptoSuite();

        return cs;
    }

    public void init(String config_path,String UserName) {
        //Create instance of client.
        client = HFClient.createNewInstance();
        try {
           // client.setCryptoSuite(CryptoSuite.Factory.getCryptoSuite(initConfig.getSMProperties()));
            CryptoSuite cs = initCryptoSuite("sw" , config_path,UserName);
            client.setCryptoSuite(cs);
        } catch (Exception e) {
            e.printStackTrace();
        }
        //Set up USERS
        File sampleStoreFile = new File(System.getProperty("java.io.tmpdir") + "/HFCSampletest.properties");
        if (sampleStoreFile.exists()) { //For testing start fresh
            sampleStoreFile.delete();
        }
        final SampleStore sampleStore = new SampleStore(sampleStoreFile);
        // get users for all orgs
        Collection<SampleOrg> testSampleOrgs = initConfig.getIntegrationSampleOrgs();
        for (SampleOrg sampleOrg : testSampleOrgs) {
            final String orgName = sampleOrg.getName();
            SampleUser admin = sampleStore.getMember(initConfig.TEST_ADMIN_NAME, orgName);
            sampleOrg.setAdmin(admin); // The admin of this org.
            // No need to enroll or register all done in End2endIt !
            SampleUser user = sampleStore.getMember(initConfig.TESTUSER_1_NAME, orgName);
            sampleOrg.addUser(user);  //Remember user belongs to this Org

            final String sampleOrgName = sampleOrg.getName();
            try {
            	System.out.println(Paths.get(sampleOrg.getKeystorePath()).toFile());
                SampleUser peerOrgAdmin = sampleStore.getMember(sampleOrgName + "Admin", sampleOrgName, sampleOrg.getMSPID(),
                        findFileSk(Paths.get(sampleOrg.getKeystorePath()).toFile()),
                        Paths.get(sampleOrg.getSigncertsPath()).toFile());
                sampleOrg.setPeerAdmin(peerOrgAdmin); //A special user that can create channels, join peers and install chaincode
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        chaincodeID = ChaincodeID.newBuilder().setName(initConfig.CHAIN_CODE_NAME).setVersion(initConfig.CHAIN_CODE_VERSION).build();
    }

    public void newChannel() throws Exception {
        SampleOrg sampleOrg = initConfig.getIntegrationSampleOrg(initConfig.orgName);
        client.setUserContext(sampleOrg.getPeerAdmin());

        channel = client.newChannel(initConfig.CHANNEL_NAME);
        for (String orderName : sampleOrg.getOrdererNames()) {
            channel.addOrderer(client.newOrderer(orderName, sampleOrg.getOrdererLocation(orderName),
                    sampleOrg.getOrdererProperties(orderName)));
        }

        for (String peerName : sampleOrg.getPeerNames()) {
            String peerLocation = sampleOrg.getPeerLocation(peerName);
            Peer peer = client.newPeer(peerName, peerLocation, sampleOrg.getPeerProperties(peerName));

            //Query the actual peer for which channels it belongs to and check it belongs to this channel
            Set<String> channels = client.queryChannels(peer);
            if (!channels.contains(initConfig.CHANNEL_NAME)) {
                throw new AssertionError(format("Peer %s does not appear to belong to channel %s", peerName, initConfig.CHANNEL_NAME));
            }

            channel.addPeer(peer);
            sampleOrg.addPeer(peer);
        }

        for (String eventHubName : sampleOrg.getEventHubNames()) {
            EventHub eventHub = client.newEventHub(eventHubName, sampleOrg.getEventHubLocation(eventHubName),
                    sampleOrg.getPeerProperties(eventHubName));
            channel.addEventHub(eventHub);
        }

        channel.initialize();
        //channel.setTransactionWaitTime(testConfig.getTransactionWaitTime());
        //channel.setDeployWaitTime(testConfig.getDeployWaitTime());
    }

    public boolean invoke(String finction,String[] args) {
        Collection<ProposalResponse> successful = new LinkedList<>();
        Collection<ProposalResponse> failed = new LinkedList<>();
        try {
            /// Send transaction proposal to all peers
            TransactionProposalRequest transactionProposalRequest = client.newTransactionProposalRequest();
            transactionProposalRequest.setChaincodeID(chaincodeID);
            transactionProposalRequest.setFcn(finction);
            //transactionProposalRequest.setProposalWaitTime(testConfig.getProposalWaitTime());
            transactionProposalRequest.setArgs(args);

            Map<String, byte[]> tm2 = new HashMap<>();
            tm2.put("HyperLedgerFabric", "TransactionProposalRequest:JavaSDK".getBytes(UTF_8));
            tm2.put("method", "TransactionProposalRequest".getBytes(UTF_8));
            tm2.put("result", ":)".getBytes(UTF_8));  /// This should be returned see chaincode.
            try {
                transactionProposalRequest.setTransientMap(tm2);
            } catch (Exception e) {
            }

            Collection<ProposalResponse> transactionPropResp = channel.sendTransactionProposal(transactionProposalRequest, channel.getPeers());
            for (ProposalResponse response : transactionPropResp) {
                if (response.getStatus() == ProposalResponse.Status.SUCCESS) {
                    // out("Successful transaction proposal response Txid: %s from peer %s", response.getTransactionID(), response.getPeer().getName());
                    successful.add(response);
                } else {
                    failed.add(response);
                }
            }

            // Check that all the proposals are consistent with each other. We should have only one set
            // where all the proposals above are consistent.
            Collection<Set<ProposalResponse>> proposalConsistencySets = SDKUtils.getProposalConsistencySets(transactionPropResp);
            if (proposalConsistencySets.size() != 1) {
                out(format("Expected only one set of consistent proposal responses but got %d", proposalConsistencySets.size()));
            }

            //out("Received %d transaction proposal responses. Successful+verified: %d . Failed: %d",
            //        transactionPropResp.size(), successful.size(), failed.size());
            if (failed.size() > 0) {
                ProposalResponse firstTransactionProposalResponse = failed.iterator().next();
                out("Invoke:" + failed.size() + " endorser error: " +
                        firstTransactionProposalResponse.getMessage() +
                        ". Was verified: " + firstTransactionProposalResponse.isVerified());
            }
            //out("Successfully received transaction proposal responses.");

            // Send Transaction Transaction to orderer
            //BlockEvent.TransactionEvent transactionEvent = channel.sendTransaction(successful).get(testConfig.getTransactionWaitTime(), TimeUnit.SECONDS);
            BlockEvent.TransactionEvent transactionEvent = channel.sendTransaction(successful).get(initConfig.getWaiteTime(), TimeUnit.SECONDS);

            if (transactionEvent.isValid()) {
                //ut("Finished transaction with transaction id %s", transactionEvent.getTransactionID());
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            //out("Caught an exception while invoking chaincode");
            e.printStackTrace();
            return false;
            //fail("Failed invoking chaincode with error : " + e.getMessage());
        }
    }

    public String query(String finction,String[] args) {
        try {
            //out("Now query chaincode for the value of b.");
            QueryByChaincodeRequest queryByChaincodeRequest = client.newQueryProposalRequest();
            queryByChaincodeRequest.setArgs(args);
            queryByChaincodeRequest.setFcn(finction);
            queryByChaincodeRequest.setChaincodeID(chaincodeID);

            Map<String, byte[]> tm2 = new HashMap<>();
            tm2.put("HyperLedgerFabric", "QueryByChaincodeRequest:JavaSDK".getBytes(UTF_8));
            tm2.put("method", "QueryByChaincodeRequest".getBytes(UTF_8));
            queryByChaincodeRequest.setTransientMap(tm2);

            Collection<ProposalResponse> queryProposals = channel.queryByChaincode(queryByChaincodeRequest, channel.getPeers());
            for (ProposalResponse proposalResponse : queryProposals) {
                if (!proposalResponse.isVerified() || proposalResponse.getStatus() != ProposalResponse.Status.SUCCESS) {
                    out("Failed query proposal from peer " + proposalResponse.getPeer().getName() + " status: " + proposalResponse.getStatus() +
                            ". Messages: " + proposalResponse.getMessage()
                            + ". Was verified : " + proposalResponse.isVerified());
                } else {
                    String payload = proposalResponse.getProposalResponse().getResponse().getPayload().toStringUtf8();
                    out("Query payload from peer %s returned %s", proposalResponse.getPeer().getName(), payload);
                    return payload;
                }
            }
        } catch (Exception e) {
            out("Caught exception while running query");
            e.printStackTrace();
            out("Failed during chaincode query with error : " + e.getMessage());
        }
        return null;
    }

    public void close() {
        channel.shutdown(true);
    }

    static void out(String format, Object... args) {
        System.err.flush();
        System.out.flush();

        System.out.println(format(format, args));
        System.err.flush();
        System.out.flush();

    }

    private static File findFileSk(File directory) {

        File[] matches = directory.listFiles((dir, name) -> name.endsWith("_sk"));

        if (null == matches) {
            throw new RuntimeException(format("Matches returned null does %s directory exist?", directory.getAbsoluteFile().getName()));
        }

        if (matches.length != 1) {
            throw new RuntimeException(format("Expected in %s only 1 sk file but found %d", directory.getAbsoluteFile().getName(), matches.length));
        }

        return matches[0];

    }

	public static void main(String[] args) throws Exception	{
		FabricManager manager = new FabricManager();
		manager.init("/Java/sdktest/Test/kcoin-sdk-config.yaml", "User1");
		manager.newChannel();
		manager.query("query", new String[] {"a"});
		manager.query("query", new String[] {"b"});
		manager.invoke("invoke", new String[] {"a","b","200"});
		manager.query("query", new String[] {"a"});
		manager.query("query", new String[] {"b"});
		manager.invoke("invoke", new String[] {"b","a","200"});
		manager.query("query", new String[] {"a"});
		manager.query("query", new String[] {"b"});
	}
}
