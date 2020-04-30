
import java.io.*;
import java.lang.System;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;

//
// This is an implementation of a simplified version of a command 
// line ftp client. The program always takes two arguments
//


public class CSftp {
    static final int MAX_LEN = 255;
    static final int ARG_CNT = 2;
    static final int DEFAULT_PORT = 21;
    static final int TIMEOUT_TEN_SECONDS = 10000;
    static final int TIMEOUT_TWENTY_SECONDS = 20000;
    static final String[] ERROR = {"error"};

    public static void main(String[] args) {
        byte cmdString[] = new byte[MAX_LEN];

        // Get command line arguments and connected to FTP
        // If the arguments are invalid or there aren't enough of them
        // then exit.

        if (args.length != ARG_CNT) {
            System.out.print("Usage: cmd ServerAddress ServerPort\n");
            return;
        }

        String hostAddress = args[0];
        int portNumber = 21;
        if (args.length == ARG_CNT) {
            portNumber = Integer.parseInt(args[1]);
        }

        try {
            Socket controlSocket = createSocket(hostAddress, portNumber, TIMEOUT_TWENTY_SECONDS);
            BufferedWriter socketWriter = new BufferedWriter(new OutputStreamWriter(controlSocket.getOutputStream()));
            BufferedReader socketReader = new BufferedReader(new InputStreamReader(controlSocket.getInputStream()));
            System.out.println("<-- " + socketReader.readLine());

            for (int len = 1; len > 0;) {
                System.out.print("csftp> ");
                len = System.in.read(cmdString);
                String input = new String(cmdString);
                cmdString = new byte[MAX_LEN];
                if (len <= 0)
                    break;
                // Start processing the command here.
                String[] cmd = processInput(input);
                if (cmd.equals(ERROR)) {
                    continue;
                }

                switch (cmd[0]) {
                    case "user":
                        sendUser(cmd[1], socketWriter, socketReader);
                        continue;
                    case "pw":
                        sendPassword(cmd[1], socketWriter, socketReader);
                        continue;
                    case "quit":
                        quit(controlSocket, socketWriter, socketReader);
                        continue;
                    case "get":
                        getRemote(cmd[1], socketWriter, socketReader);
                        continue;
                    case "features":
                        getFeatures(socketWriter, socketReader);
                        continue;
                    case "cd":
                        changeDirectory(cmd[1], socketWriter, socketReader);
                        continue;
                    case "dir":
                        printDirectory(socketWriter, socketReader);
                        continue;
                    default:
                        System.out.println("0x001 Invalid command.");
                        continue;
                }
            }
        } catch (IOException exception) {
            System.err.println("0xFFFE Input error while reading commands, terminating.");
            System.exit(1);
        } catch (Exception e) {
            System.err.println("0xFFFF Processing error. " + e.toString() + ".");
            System.exit(1);
        }
    }

    // Returns a socket given the host address and port number
    // If socket fails to connect or times out then a error message will be printed
    // and program will terminate
    public static Socket createSocket(String hostAddress, int portNumber, int timeout) {
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(hostAddress, portNumber), timeout);
            return socket;
        } catch (Exception exception) {
            if (timeout == TIMEOUT_TWENTY_SECONDS) {
                System.err.println("0xFFFC Control connection to " + hostAddress + " on port " + portNumber + " failed to open.");
                System.exit(1);
            } else if (timeout == TIMEOUT_TEN_SECONDS) {
                System.err.println("0x3A2 Data transfer connection to " + hostAddress + " on port " + portNumber + " failed to open.");
            }
        }
        return null;
    }

    // Takes in the user input and determines whether it is a valid command and returns the valid command
    public static String[] processInput(String input) {
        String[] inputArgs = input.trim().split("\\s+");
        
        if (inputArgs.length == 0 || inputArgs[0].isEmpty()) {
            return ERROR;
        }
        
        // Ignore lines starting with #
        if (inputArgs.length > 0 && inputArgs[0].charAt(0) == '#') {
            return ERROR;
        }

        if (inputArgs.length > ARG_CNT) { // Greater than 2 arguments
            System.out.println("0x002 Incorrect number of arguments.");
            return ERROR;
        }

        String command = inputArgs[0];
        boolean hasTwoArguments = inputArgs.length == 2;

        if (command.equals("quit") || command.equals("features") || command.equals("dir")) {
            if (hasTwoArguments) {
                System.out.println("0x002 Incorrect number of arguments.");
                return ERROR;
            }
        }

        if (command.equals("user") || command.equals("pw") || command.equals("get") || command.equals("cd")) {
            if (!hasTwoArguments) {
                System.out.println("0x002 Incorrect number of arguments.");
                return ERROR;
            }
        }
        return inputArgs;
    }

    // sends the USER command with the given username as an argument to the server
    public static void sendUser(String userName, BufferedWriter socketWriter, BufferedReader socketReader) {
        try {
            System.out.println("--> USER " + userName);
            socketWriter.write("USER " + userName + "\r\n");
            socketWriter.flush();
            readFromConnection(socketReader, false);
        } catch (IOException e) {
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    // sends the PASS command with the given password as an argument to the server
    public static void sendPassword(String password, BufferedWriter socketWriter, BufferedReader socketReader) {
        try {
            System.out.println("--> PASS " + password);
            socketWriter.write("PASS " + password + "\r\n");
            socketWriter.flush();
            readFromConnection(socketReader, false);
        } catch (IOException e) {
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    public static void getRemote(String fileName, BufferedWriter socketWriter, BufferedReader socketReader) {
        // requires PASV and need to use response code for handling this method
        // use FileOutputStream
        try {
            System.out.println("--> PASV");
            socketWriter.write("PASV\r\n");
            socketWriter.flush();
            ArrayList<String> response = readFromConnection(socketReader, false);
            if (!response.isEmpty() && response.get(0).contains("227")) {
                socketWriter.write("TYPE I\r\n"); // switch to binary mode
                socketWriter.flush();
                readFromConnection(socketReader, false);

                String tempPasvAddress = response.get(0);
                ArrayList<String> pasvAddress = calculatePasvAddress(tempPasvAddress);
                if (!pasvAddress.isEmpty()) {
                    Socket pasvSocket = createSocket(pasvAddress.get(0), Integer.parseInt(pasvAddress.get(1)), TIMEOUT_TEN_SECONDS);
                    if (pasvSocket != null) {
                        InputStream pasvInputStream = pasvSocket.getInputStream();
                        BufferedReader pasvReader = new BufferedReader(new InputStreamReader(pasvSocket.getInputStream()));
                        System.out.println("--> RETR " + fileName);
                        socketWriter.write("RETR " + fileName + "\r\n");
                        socketWriter.flush();

                        response = readFromConnection(socketReader, false);
                        if (response.get(0).contains("150") || response.get(0).contains("125")) {
                            byte[] buffer = new byte[4096];
                            try {
                                FileOutputStream out = new FileOutputStream(fileName, false);
                                int bytesRead;
                                do {
                                    bytesRead = pasvInputStream.read(buffer);
                                    if (bytesRead > 0) {
                                        out.write(buffer, 0, bytesRead);
                                    }
                                } while (bytesRead >= 0);
                                out.close();
                            } catch (FileNotFoundException e) {
                                System.err.println("0x38E Access to local file " + fileName + " denied.");
                            } catch (IOException e) {
                                // this catch for when the inputstream cannot read any bytes
                                System.err.println("0x3A7 Data transfer connection I/O error, closing data connection.");
                                return;
                            }
                            // need these amount of readFrom connections for things to print out properly
                            readFromConnection(socketReader, false);
                        }
                        pasvSocket.close();
                        pasvReader.close();
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    // Quits the client and closes the control socket
    public static void quit(Socket socket, BufferedWriter socketWriter, BufferedReader socketReader) {
        try {
            System.out.println("--> QUIT");
            socketWriter.write("QUIT\r\n");
            socketWriter.flush();
            readFromConnection(socketReader, false);
            if (socket.isConnected()) {
                socket.close();
                socketWriter.close();
                socketReader.close();
            }
            System.exit(0);
        } catch (IOException e) {
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    // Sends a FEAT command to the server and prints out the server response
    public static void getFeatures(BufferedWriter socketWriter, BufferedReader socketReader) {
        try {
            System.out.println("--> FEAT");
            socketWriter.write("FEAT\r\n");
            socketWriter.flush();
            readFromConnection(socketReader,false);
        } catch (IOException e) {
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    // Changes the current directory to the directory dir
    public static void changeDirectory(String dir, BufferedWriter socketWriter, BufferedReader socketReader) {
        try {
            System.out.println("--> CWD " + dir);
            socketWriter.write("CWD " + dir + "\r\n");
            socketWriter.flush();
            readFromConnection(socketReader, false);
        } catch (IOException e) {
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    // Prints out the current directory to the console
    public static void printDirectory(BufferedWriter socketWriter, BufferedReader socketReader) {
        // requires PASV and need to use response code for handling this method
        try {
            System.out.println("--> PASV");
            socketWriter.write("PASV\r\n");
            socketWriter.flush();
            ArrayList<String> response = readFromConnection(socketReader, false);
            if (!response.isEmpty()) {
                String tempPasvAddress = response.get(0);
                ArrayList<String> pasvAddress = calculatePasvAddress(tempPasvAddress);
                if (!pasvAddress.isEmpty()) {
                    Socket pasvSocket = createSocket(pasvAddress.get(0), Integer.parseInt(pasvAddress.get(1)),
                            TIMEOUT_TEN_SECONDS);
                    if (pasvSocket != null) {
                        BufferedReader pasvReader = new BufferedReader(
                                new InputStreamReader(pasvSocket.getInputStream()));
                        System.out.println("--> LIST");
                        socketWriter.write("LIST\r\n");
                        socketWriter.flush();
                        readFromConnection(socketReader, false);
                        readFromConnection(pasvReader, true);
                        readFromConnection(socketReader, false);
                        pasvReader.close();
                        pasvSocket.close();
                    }
                }
            }
        } catch (IOException e) {
            System.out.println("0xFFFD Control connection I/O error, closing control connection.");
            System.exit(1);
        }
    }

    // Parses through the server's response to a PASV command and calculates and returns
    // the address and port for the PASV socket
    public static ArrayList<String> calculatePasvAddress(String tempPasvAddress) {
        ArrayList<String> returnArray = new ArrayList<String>();
        if (!tempPasvAddress.contains("227")) { // return empty list if not the correct response code
            return returnArray;
        }

        tempPasvAddress = tempPasvAddress.substring(tempPasvAddress.indexOf("(")+1, tempPasvAddress.indexOf(")"));
        String[] addressArray = tempPasvAddress.split(",");
        String pasvHost = "";
        String pasvPort = "";
        int tempInteger;
        // can throw array out of bounds exception
        // when you type dir after you download a file
        for (int i = 0; i < 4; i++) {
            pasvHost = pasvHost.concat(addressArray[i]);
            if (i < 3) {
                pasvHost = pasvHost + ".";
            }
        }
        tempInteger = Integer.parseInt(addressArray[4])*256 + Integer.parseInt(addressArray[5]);
        pasvPort = Integer.toString(tempInteger);

        returnArray.add(pasvHost);
        returnArray.add(pasvPort);
        return returnArray;
    }

    // prints out the response from the server onto the console
    public static ArrayList<String> readFromConnection(BufferedReader socketReader, boolean isDataConnection) throws IOException {
        ArrayList<String> linesToPrint = new ArrayList<String>();
        String lineFromServer = null;
        do {
            lineFromServer = socketReader.readLine();
            if (lineFromServer == null) {
                break;
            }
            linesToPrint.add(lineFromServer);
        } while (!(lineFromServer.matches("\\d\\d\\d\\s.*")));

        for (String line : linesToPrint) {
            if (line != null) {
                if (isDataConnection) {
                    System.out.println(line);
                } else {
                    System.out.println("<-- " + line);
                }
            }
        }

        if (linesToPrint.isEmpty()) {
            throw new IOException(); // socket connection is lost
        }

        return linesToPrint;
    }
}
