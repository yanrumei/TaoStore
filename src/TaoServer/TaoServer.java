package TaoServer;

import Configuration.TaoConfigs;
import Messages.MessageCreator;
import Messages.MessageTypes;
import Messages.ProxyRequest;
import Messages.ServerResponse;
import TaoProxy.*;
import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Longs;

import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * @brief Class to represent a server for TaoStore
 */
// TODO: create interface
public class TaoServer {
    // The file object the server will interact with
    private RandomAccessFile mDiskFile;

    // The total amount of server storage in MB
    private long mServerSize;

    // Read-write lock for bucket
    // TODO: change this to regular lock. Concurrent reading of RandomAccessFile is not thread safe
    private final transient ReentrantReadWriteLock mRWL = new ReentrantReadWriteLock();
    private Object[] mPathReadLock;

    // A MessageCreator to create different types of messages to be passed from client, proxy, and server
    private MessageCreator mMessageCreator;

    // The height of the tree stored on this server
    private int mServerTreeHeight;

    private long mTimestamp;

    /**
     * @brief Default constructor
     */
    public TaoServer(long minServerSize, MessageCreator messageCreator) {
        try {
            // Initialize needed constants
            TaoConfigs.initConfiguration(minServerSize);

            mTimestamp = 0;

            // Mke sure the amount of servers being used are a power of 2
            int numServers = TaoConfigs.PARTITION_SERVERS.size();
            if ((numServers & -numServers) != numServers) {
                // TODO: only use a power of two of the servers
            }

            // Calculate the tree height
            mServerTreeHeight = TaoConfigs.TREE_HEIGHT;
            if (numServers > 1) {
                int levelSavedOnProxy = (numServers / 2);
                mServerTreeHeight -= levelSavedOnProxy;
            }

            int numPaths = mServerTreeHeight;
            mPathReadLock = new Object[1];

            // Create file object which the server will interact with
            mDiskFile = new RandomAccessFile(TaoConfigs.ORAM_FILE, "rwd");

            // Assign message creator
            mMessageCreator = messageCreator;

            // Calculate the total amount of space the tree will use
            mServerSize = ServerUtility.calculateSize(mServerTreeHeight, TaoConfigs.ENCRYPTED_BUCKET_SIZE);



            // Allocate space
            mDiskFile.setLength(mServerSize);
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    /**
     * @brief Method which will read the path with the specified pathID from disk and return the result
     * @param pathID
     * @return the bytes of the desired path
     */
    public byte[] readPath(long pathID) {
        // Array of byte arrays (buckets expressed as byte array)
        byte[][] pathInBytes = new byte[mServerTreeHeight + 1][];
        TaoLogger.logForce("Starting readPath for " + pathID);
        try {
            // Acquire read lock
            mRWL.writeLock().lock();

            // Get the directions for this path
            boolean[] pathDirection = ServerUtility.getPathFromPID(pathID, mServerTreeHeight);

            // Variable to represent the offset into the disk file
            long offset = 0;

            // Index into logical array representing the ORAM tree
            long index = 0;

            // The current bucket we are looking for
            int currentBucket = 0;

            // Seek into the file
            mDiskFile.seek(offset);

            int mBucketSize = (int) TaoConfigs.ENCRYPTED_BUCKET_SIZE;

            // Allocate byte array for this bucket
            pathInBytes[currentBucket] = new byte[mBucketSize];
//            System.out.println("Bucket before read");
//            for (Byte b : pathInBytes[currentBucket]) {
//                System.out.print(b);
//            }
//            System.out.println();
            // Read bytes from the disk file into the byte array for this bucket
            TaoLogger.logForce("1 readPath for " + pathID);
            TaoLogger.logForce("1 Trying to read in " + pathInBytes[currentBucket].length + " bytes");
            TaoLogger.logForce("1 Trying to read with offset " + offset);
            mDiskFile.readFully(pathInBytes[currentBucket]);
//            System.out.println("Bucket after read");
//            for (Byte b : pathInBytes[currentBucket]) {
//                System.out.print(b);
//            }
//            System.out.println();
            // Increment the current bucket
            currentBucket++;

            // Visit the rest of the buckets
            for (Boolean right : pathDirection) {
                // Navigate the array representing the tree
                if (right) {
                    TaoLogger.logForce("read right");
                    offset = (2 * index + 2) * mBucketSize;
                    index = offset / mBucketSize;
                } else {
                    TaoLogger.logForce("read left");
                    offset = (2 * index + 1) * mBucketSize;
                    index = offset / mBucketSize;
                }

                // Seek into file
                mDiskFile.seek(offset);

                // Allocate byte array for this bucket
                pathInBytes[currentBucket] = new byte[mBucketSize];

                // Read bytes from the disk file into the byte array for this bucket
                TaoLogger.logForce("2 readPath for " + pathID);
                TaoLogger.logForce("2 Trying to read in " + pathInBytes[currentBucket].length + " bytes");
                TaoLogger.logForce("2 Trying to read with offset " + offset);
                mDiskFile.readFully(pathInBytes[currentBucket]);

                // Increment the current bucket
                currentBucket++;
            }

            // Release the read lock
            mRWL.writeLock().unlock();

            // Put first bucket into a new byte array representing the final return value
            byte[] returnData = pathInBytes[0];

            // Add every bucket into the new byte array
            for (int i = 1; i < pathInBytes.length; i++) {
                returnData = Bytes.concat(returnData, pathInBytes[i]);
            }

            returnData = Bytes.concat(Longs.toByteArray(pathID), returnData);
            // Return complete path
            TaoLogger.logForce("Ending read path");

//            for (Byte b : returnData) {
//                System.out.println(b);
//            }

            return returnData;
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Return null if there is an error
        return null;
    }

    /**
     * @brief Method to write data to the specified file
     * @param pathID
     * @param data
     * @return if the write was successful or not
     */
    public boolean writePath(long pathID, byte[] data) {
        try {
            TaoLogger.logForce("skeddit Trying to write a path of size " + data.length);
            // Acquire write lock
            mRWL.writeLock().lock();

            // Get the directions for this path
            boolean[] pathDirection = ServerUtility.getPathFromPID(pathID, mServerTreeHeight);

            // Variable to represent the offset into the disk file
            long offsetInDisk = 0;

            // Index into logical array representing the ORAM tree
            long indexIntoTree = 0;

            // Indices into the data byte array
            int dataIndexStart = 0;
            int dataIndexStop = (int) TaoConfigs.ENCRYPTED_BUCKET_SIZE;
            TaoLogger.log("The dataIndexStart is " + dataIndexStart);
            TaoLogger.log("The dataIndexStop is " + dataIndexStop);

            // Seek into the file
            mDiskFile.seek(offsetInDisk);

            TaoLogger.logForce("1 writePath for " + pathID);
            TaoLogger.logForce("1 Trying to write " + (dataIndexStop - dataIndexStart) + " bytes");
            TaoLogger.logForce("1 Trying to write with offset " + offsetInDisk);

            // Write bucket to disk
            mDiskFile.write(Arrays.copyOfRange(data, dataIndexStart, dataIndexStop));

            int mBucketSize = (int) TaoConfigs.ENCRYPTED_BUCKET_SIZE;

            // Increment indices
            dataIndexStart += mBucketSize;
            dataIndexStop += mBucketSize;


            // Write the rest of the buckets
            for (Boolean right : pathDirection) {
                // Navigate the array representing the tree
                if (right) {
                    TaoLogger.log("write right");
                    offsetInDisk = (2 * indexIntoTree + 2) * mBucketSize;
                    indexIntoTree = offsetInDisk / mBucketSize;
                } else {
                    TaoLogger.log("write left");
                    offsetInDisk = (2 * indexIntoTree + 1) * mBucketSize;
                    indexIntoTree = offsetInDisk / mBucketSize;
                }

                // Seek into disk
                mDiskFile.seek(offsetInDisk);

                byte[] dataToWrite = Arrays.copyOfRange(data, dataIndexStart, dataIndexStop);

                TaoLogger.logForce("2 writePath for " + pathID);
                TaoLogger.logForce("2 Trying to write " + (dataIndexStop - dataIndexStart) + " bytes");
                TaoLogger.logForce("2 Trying to write with offset " + offsetInDisk);

                // Write bucket to disk
                mDiskFile.write(dataToWrite);

                // Increment indices
                dataIndexStart += mBucketSize;
                dataIndexStop += mBucketSize;

            }

            // Release the write lock
            mRWL.writeLock().unlock();

            // Return true, signaling that the write was successful
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Return false, signaling that the write was not successful
        return false;
    }

    /**
     * @brief Method to run proxy indefinitely
     */
    public void run() {
        try {
            // Create a thread pool for asynchronous sockets
            AsynchronousChannelGroup threadGroup =
                    AsynchronousChannelGroup.withFixedThreadPool(TaoConfigs.PROXY_THREAD_COUNT, Executors.defaultThreadFactory());

            // Create a channel
            AsynchronousServerSocketChannel channel =
                    AsynchronousServerSocketChannel.open(threadGroup).bind(new InetSocketAddress(TaoConfigs.SERVER_PORT));

            TaoLogger.logForce("Waiting for a connection");
            // Asynchronously wait for incoming connections
            channel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel proxyChannel, Void att){
                    TaoLogger.logForce("---!!!--- GOT A CONNECTION ---!!!---");
                    // Start listening for other connections
                    channel.accept(null, this);

                    Runnable serializeProcedure = () -> serveProxy(proxyChannel);
                    new Thread(serializeProcedure).start();
                }
                @Override
                public void failed(Throwable exc, Void att) {
                    // TODO: Implement?
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void serveProxy(AsynchronousSocketChannel channel) {
        try {
            // Create byte buffer to use to read incoming message type and size
            ByteBuffer messageTypeAndSize = ByteBuffer.allocate(4 + 4);

            // Asynchronously read message
            channel.read(messageTypeAndSize, null, new CompletionHandler<Integer, Void>() {
                @Override
                public void completed(Integer result, Void attachment) {
                    TaoLogger.logForce("Reading message, remainder == " + messageTypeAndSize.remaining());
                    TaoLogger.logForce("Got message header");
                    // Flip buffer for reading
                    messageTypeAndSize.flip();

                    // Parse the message type and size from server
                    byte[] messageTypeBytes = new byte[4];
                    byte[] messageLengthBytes = new byte[4];
                    messageTypeAndSize.get(messageTypeBytes);
                    messageTypeAndSize.get(messageLengthBytes);
                    int messageType = Ints.fromByteArray(messageTypeBytes);
                    int messageLength = Ints.fromByteArray(messageLengthBytes);

                    // Receive rest of message
                    ByteBuffer message = ByteBuffer.allocate(messageLength);
                    channel.read(message, null, new CompletionHandler<Integer, Void>() {
                        @Override
                        public void completed(Integer result, Void attachment) {
                            // Makre sure to read entire message
                            while (message.remaining() > 0) {
                                channel.read(message, null, this);
                                return;
                            }
                            TaoLogger.logForce("Got rest of message");
                            // Flip buffer for reading
                            message.flip();

                            // Get bytes from message
                            byte[] requestBytes = new byte[messageLength];
                            message.get(requestBytes);

                            // Create proxy read request from bytes
                            ProxyRequest proxyReq = mMessageCreator.parseProxyRequestBytes(requestBytes);

                            ByteBuffer messageTypeAndLengthBuffer = null;
                            byte[] messageTypeAndLength = null;
                            byte[] serializedResponse = null;

                            // Check message type
                            if (messageType == MessageTypes.PROXY_READ_REQUEST) {
                                TaoLogger.logForce("Serving a read request");
                                // Read the request path
                                byte[] returnPathData = readPath(proxyReq.getPathID());

                                // Create a server response
                                ServerResponse readResponse = mMessageCreator.createServerResponse();
                                readResponse.setPathID(proxyReq.getPathID());
                                readResponse.setPathBytes(returnPathData);

                                // Send response to proxy
                                serializedResponse = readResponse.serialize();

                                byte[] messageTypeBytes = Ints.toByteArray(MessageTypes.SERVER_RESPONSE);
                                byte[] messageLengthBytes = Ints.toByteArray(serializedResponse.length);

                                messageTypeAndLength = Bytes.concat(messageTypeBytes, messageLengthBytes);
                                messageTypeAndLengthBuffer = ByteBuffer.wrap(messageTypeAndLength);
                            } else if (messageType == MessageTypes.PROXY_WRITE_REQUEST) {
                                TaoLogger.logForce("Serving a write request");

                                boolean success = true;
                                if (proxyReq.getTimestamp() >= mTimestamp) {
                                    mTimestamp = proxyReq.getTimestamp();
                                    // Write each path
                                    byte[] dataToWrite = proxyReq.getDataToWrite();

                                    int pathSize = proxyReq.getPathSize();
                                    int startIndex = 0;
                                    int endIndex = pathSize;
                                    byte[] currentPath;
                                    long currentPathID;
                                    while (startIndex < dataToWrite.length) {
                                        currentPath = Arrays.copyOfRange(dataToWrite, startIndex, endIndex);
                                        currentPathID = Longs.fromByteArray(Arrays.copyOfRange(currentPath, 0, 8));

                                        startIndex += pathSize;
                                        endIndex += pathSize;
                                        byte[] encryptedPath = Arrays.copyOfRange(currentPath, 8, currentPath.length);

                                        if (!writePath(currentPathID, encryptedPath)) {
                                            success = false;
                                        }
                                    }
                                } else {
                                    // The received write request is old, still return true since future writes
                                    // have already been received
                                    success = true;
                                }
                                // Create a server response
                                ServerResponse writeResponse = mMessageCreator.createServerResponse();
                                writeResponse.setIsWrite(success);

                                // Send response to proxy
                                serializedResponse = writeResponse.serialize();

                                // First we send the message type to the server along with the size of the message
                                byte[] messageTypeBytes = Ints.toByteArray(MessageTypes.SERVER_RESPONSE);
                                byte[] messageLengthBytes = Ints.toByteArray(serializedResponse.length);

                                messageTypeAndLength = Bytes.concat(messageTypeBytes, messageLengthBytes);
                                messageTypeAndLengthBuffer = ByteBuffer.wrap(messageTypeAndLength);
                            } else if (messageType == MessageTypes.PROXY_INITIALIZE_REQUEST) {
                                // Write each path
                                boolean success = true;
                                byte[] dataToWrite = proxyReq.getDataToWrite();

                                int pathSize = proxyReq.getPathSize();
                                int startIndex = 0;
                                int endIndex = pathSize;
                                byte[] currentPath;
                                long currentPathID;
                                while (startIndex < dataToWrite.length) {
                                    currentPath = Arrays.copyOfRange(dataToWrite, startIndex, endIndex);
                                    currentPathID = Longs.fromByteArray(Arrays.copyOfRange(currentPath, 0, 8));

                                    startIndex += pathSize;
                                    endIndex += pathSize;
                                    byte[] encryptedPath = Arrays.copyOfRange(currentPath, 8, currentPath.length);

                                    if (!writePath(currentPathID, encryptedPath)) {
                                        success = false;
                                    }
                                }

                                // Create a server response
                                ServerResponse writeResponse = mMessageCreator.createServerResponse();
                                writeResponse.setIsWrite(success);

                                // Send response to proxy
                                serializedResponse = writeResponse.serialize();

                                // First we send the message type to the server along with the size of the message
                                byte[] messageTypeBytes = Ints.toByteArray(MessageTypes.SERVER_RESPONSE);
                                byte[] messageLengthBytes = Ints.toByteArray(serializedResponse.length);

                                messageTypeAndLength = Bytes.concat(messageTypeBytes, messageLengthBytes);
                                messageTypeAndLengthBuffer = ByteBuffer.wrap(messageTypeAndLength);
                            }

                            ByteBuffer returnMessageBuffer = ByteBuffer.wrap(Bytes.concat(messageTypeAndLength, serializedResponse));
                            messageTypeAndLengthBuffer.array();
                            // First we send the message type to the proxy along with the size of the message
                            channel.write(returnMessageBuffer, null, new CompletionHandler<Integer, Void>() {
                                @Override
                                public void completed(Integer result, Void attachment) {
                                    TaoLogger.log("sent some data, possibly all");
                                   // TaoLogger.logForce("Responded, wrote " + result + " bytes");
                                    boolean restartLoop = true;
                                    if (returnMessageBuffer.remaining() > 0) {
                                      //  TaoLogger.logForce("did not send all the data, still have " + returnMessageBuffer.remaining());
                                        channel.write(returnMessageBuffer, null, this);
                                        restartLoop = false;
                                        //  return;
                                    }

                                    if (messageType != MessageTypes.PROXY_INITIALIZE_REQUEST && restartLoop) {
                                        TaoLogger.logForce("Do i got in here?");
                                        serveProxy(channel);
                                    } else {
                                        TaoLogger.logForce("I should have gone away");
                                    }
                                }
                                @Override
                                public void failed(Throwable exc, Void attachment) {
                                    TaoLogger.logForce("Do i got in here? 1");
                                    serveProxy(channel);
                                }
                            });
                        }
                        @Override
                        public void failed(Throwable exc, Void attachment) {
                            TaoLogger.logForce("Do i got in here? 2");
                            serveProxy(channel);
                        }
                    });
                }
                @Override
                public void failed(Throwable exc, Void attachment) {
                    TaoLogger.logForce("Do i got in here? 3");
                    serveProxy(channel);
                }
            });
        } catch (Exception e) {
            TaoLogger.logForce("Do i got in here? 4");
            e.printStackTrace();
            serveProxy(channel);
        }
    }

    public static void main(String[] args) {
        TaoLogger.logOn = false;
        // Make sure user provides a storage size
        if (args.length != 1) {
            System.out.println("Please provide desired size of storage in MB");
            return;
        }

        // Create server and run
        TaoServer server = new TaoServer(Long.parseLong(args[0]), new TaoMessageCreator());
        server.run();
    }
}
