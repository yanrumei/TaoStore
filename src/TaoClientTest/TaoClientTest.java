package TaoClientTest;

import TaoClient.TaoClient;
import TaoProxy.Constants;
import TaoProxy.TaoProxy;
import TaoProxyTest.TestServer;
import TaoServer.TaoServer;
import com.google.common.primitives.Bytes;
import org.junit.Test;

import java.util.Arrays;
import static org.junit.Assert.*;

/**
 * Created by ajmagat on 5/3/16.
 */
public class TaoClientTest {
    @Test
    public void testReadWrite() {
        // Set system size
        long systemSize = 246420;

        // Create and run server
        Runnable serverRunnable = () -> {
            // Create server
            TaoServer server = new TaoServer(systemSize);

            // Run server
            server.run();
        };
        new Thread(serverRunnable).start();

        Runnable proxyRunnable = () -> {
            // Create proxy
            TaoProxy proxy = new TaoProxy(systemSize);

            // Run proxy
            proxy.run();
        };
        new Thread(proxyRunnable).start();

        // Wait 1 second for the server and proxy to come up
        try {
            Thread.sleep(1000);
        } catch (Exception e) {
            e.printStackTrace();
        }

        TaoClient client = new TaoClient();

        // Send write request
        long blockID = 3;
        byte[] dataToWrite = new byte[Constants.BLOCK_SIZE];
        Arrays.fill(dataToWrite, (byte) blockID);
        System.out.println("@@@@@@@@@@@@ Going to send write request for " + blockID);
        boolean writeStatus = client.write(blockID, dataToWrite);
        assertTrue(writeStatus);



        // Wait 1 second for the server and proxy to come up
//        try {
//            Thread.sleep(5000);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }
        System.out.println("\n\n");

        System.out.println("@@@@@@@@@@@@ Going to send read request for " + blockID);
        byte[] s = client.read(blockID);

        System.out.println("Read request for blockID " + blockID + " has data:");
//        try {
//            Thread.sleep(3000);
//        } catch (Exception e) {
//            e.printStackTrace();
//        }

        for (byte b : s) {
            System.out.print(b);
        }
        System.out.println("\n\n\n\n");
//
//        blockID = 5;
//        byte[] dataToWrite1 = new byte[Constants.BLOCK_SIZE];
//        Arrays.fill(dataToWrite1, (byte) blockID);
//        System.out.println("Going to send write request for " + blockID);
//        writeStatus = client.write(blockID, dataToWrite1);
//        assertTrue(writeStatus);
//
//        System.out.println("\n");
//
//        System.out.println("Read request for blockID " + blockID + " has data:");
//        byte[] w = client.read(blockID);
//
//        System.out.println("Read request for blockID " + blockID + " has data:");
//        for (byte b : w) {
//            System.out.print(b);
//        }
//        System.out.println("\n\n\n\n");
//
//        blockID = 3;
//        System.out.println("Going to send read request for " + blockID);
//        byte[] t = client.read(blockID);
//
//        System.out.println("Read request for blockID " + blockID + " has data:");
//        for (byte b : t) {
//            System.out.print(b);
//        }
//        System.out.println();
//



//        for (int i = 0; i < 1000; i++) {
//            //Runnable testRunnable = () -> {
//                byte[] z = client.read(blockID);
//
//                System.out.println("11 Read request for blockID " + blockID + " has data:");
//                for (byte b : z) {
//                    System.out.print(b);
//                }
//                System.out.println();
//                assertTrue(Arrays.equals(dataToWrite, z));
//           // };
//          //  new Thread(testRunnable).start();
//        }
        // Create a read request
    }
}