package TaoClientTest;

import Configuration.TaoConfigs;
import Messages.MessageCreator;
import TaoClient.TaoClient;
import TaoProxy.TaoProxy;
import TaoProxy.PathCreator;
import TaoProxy.*;
import TaoServer.TaoServer;
import org.junit.Test;

import java.util.Arrays;
import static org.junit.Assert.*;

/**
 * Created by ajmagat on 5/3/16.
 */
public class TaoClientTest {
    @Test
    public void testReadWrite() {
        TaoConfigs.PARTITION_SERVERS = TaoConfigs.TEST_PARTITION_SERVERS;
        TaoConfigs.PROXY_HOSTNAME = "localhost";

        // Set system size
        long systemSize = 246420;

        // Create and run server
        Runnable serverRunnable = () -> {
            // Create server
            MessageCreator m = new TaoMessageCreator();
            TaoServer server = new TaoServer(systemSize, m);

            // Run server
            server.run();
        };
        new Thread(serverRunnable).start();

        try{
            Thread.sleep(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        Runnable proxyRunnable = () -> {
            // Create proxy
            MessageCreator n = new TaoMessageCreator();
            PathCreator p = new TaoBlockCreator();
            Subtree s = new TaoSubtree();
            TaoProxy proxy = new TaoProxy(systemSize, n, p, s);

            proxy.initializeServer();
            proxy.run();
        };
        new Thread(proxyRunnable).start();

        // Wait 5 seconds for the server and proxy to come up
        try {
            Thread.sleep(5000);
        } catch (Exception e) {
            e.printStackTrace();
        }

       // System.out.println("done sleeping");
        TaoClient client = new TaoClient();

        TaoLogger.logForce("start");
        // Send write request
        long blockID = 3;
        byte[] dataToWrite = new byte[TaoConfigs.BLOCK_SIZE];
        Arrays.fill(dataToWrite, (byte) blockID);
        boolean writeStatus = client.write(blockID, dataToWrite);
        assertTrue(writeStatus);

        // Send write request
        blockID = 6;
        byte[] dataToWrite1 = new byte[TaoConfigs.BLOCK_SIZE];
        Arrays.fill(dataToWrite1, (byte) blockID);
        boolean writeStatus1 = client.write(blockID, dataToWrite1);
        assertTrue(writeStatus1);

        for (int i = 0; i < 1000; i++) {
            if (i % 2 == 0) {
                blockID = 3;
            } else {
                blockID = 6;
            }
            byte[] z = client.read(blockID);

            if (i % 2 == 0) {
                assertTrue(Arrays.equals(dataToWrite, z));
            } else {
                assertTrue(Arrays.equals(dataToWrite1, z));
            }
        }
        TaoLogger.logForce("end");
    }
}