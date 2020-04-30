package ca.ubc.cs.cs317.dnslookup;

import java.io.Console;
import java.net.DatagramSocket;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.util.*;

public class DNSLookupService {

    private static final int DEFAULT_DNS_PORT = 53;
    private static final int MAX_INDIRECTION_LEVEL = 10;

    private static InetAddress rootServer;
    private static boolean verboseTracing = false;
    private static DatagramSocket socket;

    private static DNSCache cache = DNSCache.getInstance();

    private static Random random = new Random();
    private static final int UPPER_QUERY_ID_BOUND = 65535;
    private static final int ASCII_PERIOD = 0x2e;
    private static int globalIndirectionLevel = 0;

    /**
     * Main function, called when program is first invoked.
     *
     * @param args list of arguments specified in the command line.
     */
    public static void main(String[] args) {

        if (args.length != 1) {
            System.err.println("Invalid call. Usage:");
            System.err.println("\tjava -jar DNSLookupService.jar rootServer");
            System.err.println("where rootServer is the IP address (in dotted form) of the root DNS server to start the search at.");
            System.exit(1);
        }

        try {
            rootServer = InetAddress.getByName(args[0]);
            System.out.println("Root DNS server is: " + rootServer.getHostAddress());
        } catch (UnknownHostException e) {
            System.err.println("Invalid root server (" + e.getMessage() + ").");
            System.exit(1);
        }

        try {
            socket = new DatagramSocket();
            socket.setSoTimeout(5000);
        } catch (SocketException ex) {
            ex.printStackTrace();
            System.exit(1);
        }

        Scanner in = new Scanner(System.in);
        Console console = System.console();
        do {
            // Use console if one is available, or standard input if not.
            String commandLine;
            if (console != null) {
                System.out.print("DNSLOOKUP> ");
                commandLine = console.readLine();
            } else
                try {
                    commandLine = in.nextLine();
                } catch (NoSuchElementException ex) {
                    break;
                }
            // If reached end-of-file, leave
            if (commandLine == null) break;

            // Ignore leading/trailing spaces and anything beyond a comment character
            commandLine = commandLine.trim().split("#", 2)[0];

            // If no command shown, skip to next command
            if (commandLine.trim().isEmpty()) continue;

            String[] commandArgs = commandLine.split(" ");

            if (commandArgs[0].equalsIgnoreCase("quit") ||
                    commandArgs[0].equalsIgnoreCase("exit"))
                break;
            else if (commandArgs[0].equalsIgnoreCase("server")) {
                // SERVER: Change root nameserver
                if (commandArgs.length == 2) {
                    try {
                        rootServer = InetAddress.getByName(commandArgs[1]);
                        System.out.println("Root DNS server is now: " + rootServer.getHostAddress());
                    } catch (UnknownHostException e) {
                        System.out.println("Invalid root server (" + e.getMessage() + ").");
                        continue;
                    }
                } else {
                    System.out.println("Invalid call. Format:\n\tserver IP");
                    continue;
                }
            } else if (commandArgs[0].equalsIgnoreCase("trace")) {
                // TRACE: Turn trace setting on or off
                if (commandArgs.length == 2) {
                    if (commandArgs[1].equalsIgnoreCase("on"))
                        verboseTracing = true;
                    else if (commandArgs[1].equalsIgnoreCase("off"))
                        verboseTracing = false;
                    else {
                        System.err.println("Invalid call. Format:\n\ttrace on|off");
                        continue;
                    }
                    System.out.println("Verbose tracing is now: " + (verboseTracing ? "ON" : "OFF"));
                } else {
                    System.err.println("Invalid call. Format:\n\ttrace on|off");
                    continue;
                }
            } else if (commandArgs[0].equalsIgnoreCase("lookup") ||
                    commandArgs[0].equalsIgnoreCase("l")) {
                // LOOKUP: Find and print all results associated to a name.
                RecordType type;
                if (commandArgs.length == 2)
                    type = RecordType.A;
                else if (commandArgs.length == 3)
                    try {
                        type = RecordType.valueOf(commandArgs[2].toUpperCase());
                    } catch (IllegalArgumentException ex) {
                        System.err.println("Invalid query type. Must be one of:\n\tA, AAAA, NS, MX, CNAME");
                        continue;
                    }
                else {
                    System.err.println("Invalid call. Format:\n\tlookup hostName [type]");
                    continue;
                }
                findAndPrintResults(commandArgs[1], type);
            } else if (commandArgs[0].equalsIgnoreCase("dump")) {
                // DUMP: Print all results still cached
                cache.forEachNode(DNSLookupService::printResults);
            } else {
                System.err.println("Invalid command. Valid commands are:");
                System.err.println("\tlookup fqdn [type]");
                System.err.println("\ttrace on|off");
                System.err.println("\tserver IP");
                System.err.println("\tdump");
                System.err.println("\tquit");
                continue;
            }

        } while (true);

        in.close();
        socket.close();
        System.out.println("Goodbye!");
    }

    /**
     * Finds all results for a host name and type and prints them on the standard output.
     *
     * @param hostName Fully qualified domain name of the host being searched.
     * @param type     Record type for search.
     */
    private static void findAndPrintResults(String hostName, RecordType type) {
        DNSNode node = new DNSNode(hostName, type);
        printResults(node, getResults(node, 0));
        globalIndirectionLevel = 0;
    }

    /**
     * Finds all the result for a specific node.
     *
     * @param node             Host and record type to be used for search.
     * @param indirectionLevel Control to limit the number of recursive calls due to CNAME redirection.
     *                         The initial call should be made with 0 (zero), while recursive calls for
     *                         regarding CNAME results should increment this value by 1. Once this value
     *                         reaches MAX_INDIRECTION_LEVEL, the function prints an error message and
     *                         returns an empty set.
     * @return A set of resource records corresponding to the specific query requested.
     */
    private static Set<ResourceRecord> getResults(DNSNode node, int indirectionLevel) {

        if (indirectionLevel > MAX_INDIRECTION_LEVEL) {
            System.err.println("Maximum number of indirection levels reached.");
            return Collections.emptySet();
        }

        // TODO To be completed by the student
        Set<ResourceRecord> results = cache.getCachedResults(node);
        if (!results.isEmpty()) { // check if cache already contains results
            return results;
        }

        retrieveResultsFromServer(node, rootServer);
        return cache.getCachedResults(node);
    }

    /**
     * Retrieves DNS results from a specified DNS server. Queries are sent in iterative mode,
     * and the query is repeated with a new server if the provided one is non-authoritative.
     * Results are stored in the cache.
     *
     * @param node   Host name and record type to be used for the query.
     * @param server Address of the server to be used for the query.
     */
    private static void retrieveResultsFromServer(DNSNode node, InetAddress server) {
        // TODO To be completed by the student
        try {
            DNSResponse dnsResponse = performQuery(node, server);
            if (dnsResponse.getAuthoritative()) {
                handleCnameQuery(node, dnsResponse);
            } 
            if (dnsResponse.getAnswerRecords().isEmpty()) {
                handleNameServerQuery(node, dnsResponse);
            }
        } catch (Exception e) {
            return;
        }
    }

    /**
     * Handles resolving cname queries
     */
    public static void handleCnameQuery(DNSNode node, DNSResponse dnsResponse) {
        ResourceRecord cnameRR = dnsResponse.getAnswerRecords().get(0);
        if (cnameRR.getType() == RecordType.CNAME) {
            Set<ResourceRecord> answerRecords = getResults(new DNSNode(cnameRR.getTextResult(), node.getType()), ++globalIndirectionLevel);
            // Cache results
            for (ResourceRecord answerRecord : answerRecords) {
                if (answerRecord.getType() == RecordType.A || answerRecord.getType() == RecordType.AAAA) {
                    cache.addResult(new ResourceRecord(node.getHostName(), node.getType(), answerRecord.getTTL(),
                            answerRecord.getInetResult()));
                } else {
                    cache.addResult(new ResourceRecord(node.getHostName(), node.getType(), answerRecord.getTTL(),
                            answerRecord.getTextResult()));
                }
            }
        }
    }
    
    /**
     * Handles resolving additional name server queries
     */
    private static void handleNameServerQuery(DNSNode node, DNSResponse dnsResponse) {
        for (ResourceRecord nextNameServer : dnsResponse.getAuthorityRecords()) {
            // No additional information, query name server for next IP
            if (dnsResponse.getAdditionalRecords().isEmpty()) {
                Set<ResourceRecord> results = getResults(new DNSNode(nextNameServer.getTextResult(), RecordType.A), globalIndirectionLevel);
                for (ResourceRecord resourceRecord : results) {
                    if (resourceRecord.getHostName().equals(nextNameServer.getTextResult())
                            && (resourceRecord.getType() == RecordType.A || resourceRecord.getType() == RecordType.AAAA)
                            && resourceRecord.getInetResult() != null) {
                        retrieveResultsFromServer(node, resourceRecord.getInetResult());
                        return;
                    }
                }
            }
            // Additional information contains IP, match domain name to find next IP to
            // query
            for (ResourceRecord additionalRecord : dnsResponse.getAdditionalRecords()) {
                if (additionalRecord.getHostName().equals(nextNameServer.getTextResult())
                        && additionalRecord.getType() == RecordType.A) {
                    retrieveResultsFromServer(node, additionalRecord.getInetResult());
                    return;
                }
            }
        }
    }

    /**
     * Performs a DNS query with given node and server IP
     * 
     * @return a DSNResponse object
     */
    private static DNSResponse performQuery(DNSNode node, InetAddress server) throws Exception {
        byte[] responseBuffer = new byte[1024];
        DNSQuery dnsQuery = encodeQuery(node, generateQueryID(), server);
        DatagramPacket responsePacket = new DatagramPacket(responseBuffer, responseBuffer.length);
        try {
            sendQuery(dnsQuery);
            socket.receive(responsePacket);
        } catch (SocketTimeoutException e) {
                sendQuery(dnsQuery);
                socket.receive(responsePacket);
        }

        DNSResponse dnsResponse = readDNSResponse(responseBuffer);
        if (verboseTracing) {
            printDNSResponse(dnsResponse);
        }
        checkRCodeErrors(dnsResponse);

        return dnsResponse;
    }

    /**
     * If verboseTracing is true then print out details of the query. 
     * Creates a DatagramPacket with the information from the DNSQuery object and 
     * sends it through the socket.
     *
     * @param dnsQuery a DNSQuery object 
     */
    private static void sendQuery(DNSQuery dnsQuery) throws IOException {
        if (verboseTracing) {
            System.out.println("\n\n" + "Query ID     " + dnsQuery.getQueryID() + " " + dnsQuery.getDomainName() + "  "
                    + dnsQuery.getRecordType().name() + " --> " + dnsQuery.getIpAddress().getHostAddress());
        }

        DatagramPacket queryPacket = new DatagramPacket(dnsQuery.getDnsRequest(), dnsQuery.getDnsRequest().length,
                dnsQuery.getIpAddress(), DEFAULT_DNS_PORT);

        socket.send(queryPacket);
    }

    /**
     * Generates a random queryID that ranges from 0 to 65535
     *
     * @return returns a queryID 
     */
    private static int generateQueryID() {
        return random.nextInt(UPPER_QUERY_ID_BOUND + 1);
    }

    /**
     * Creates a encode query as a byte array given the node and queryID
     * for asking questions to the DNS server.
     *
     * @param node   Host name and record type to be used for the query.
     * @param queryID query ID for this encoded query.
     *
     * @return encoded query as a byte array
     */
    private static DNSQuery encodeQuery(DNSNode node, int queryID, InetAddress server) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        DataOutputStream outputStream = new DataOutputStream(byteStream);

        // Header Section
        // Query ID
        outputStream.writeShort(queryID);
        // QR OpCode AA TC RD RA Z R CODE (Query parameters)
        outputStream.writeShort(0x0000);
        // QDCOUNT
        outputStream.writeShort(0x0001);
        // ANCOUNT
        outputStream.writeShort(0x0000);
        // NSCOUNT
        outputStream.writeShort(0x0000);
        // ARCOUNT
        outputStream.writeShort(0x0000);

        // Question Section
        // process host information into query
        String qName = node.getHostName();
        String[] domainParts = qName.split("\\.");
        for (String part : domainParts) {
            byte[] domainBytes = part.getBytes("UTF-8");
            outputStream.writeByte(domainBytes.length);
            outputStream.write(domainBytes);
        }
        // end of qName add 00
        outputStream.writeByte(0x00);
        // QTYPE get record type from node
        outputStream.writeShort(node.getType().getCode());
        // QCLASS
        outputStream.writeShort(0x0001);

        return new DNSQuery(queryID, qName, server, node.getType(), byteStream.toByteArray());
    }

    /**
     * Reads the DNS response given a byte array of the response
     * decodes the answers, authorityRR and additionalRR and puts 
     * them in cache 
     *
     * @param response DNS response as a byte array 
     * @return queryID of the response as an int
     */
    private static DNSResponse readDNSResponse(byte[] response) throws IOException {
        DataInputStream inStream = new DataInputStream(new ByteArrayInputStream(response));
        // decode header
        int queryID = inStream.readUnsignedShort();
        short flags = inStream.readShort();
        boolean authoritative = (flags & 0x400) != 0; // check AA bit for if authoritative response
        short questions = inStream.readShort();
        short answers = inStream.readShort();
        short authorityRR = inStream.readShort();
        short additionalRR = inStream.readShort();
        int rCode = flags & 0b1111;     

        DNSResponse dnsResponse = new DNSResponse(queryID, authoritative, rCode);

        for (int i = 0; i < questions; i++) {
            readQuestion(inStream, response);
        }

        for (int i = 0; i < answers; i++) {
            ResourceRecord answer = readResourceRecord(inStream, response);
            dnsResponse.addAnswerRecord(answer);
            cache.addResult(answer);
        }

        for (int i = 0; i < authorityRR; i++) {
            ResourceRecord authority = readResourceRecord(inStream, response);
            dnsResponse.addAuthorityRecord(authority);
            cache.addResult(authority);
        }

        for (int i = 0; i < additionalRR; i++) {
            ResourceRecord additional = readResourceRecord(inStream, response);
            dnsResponse.addAdditionalRecord(additional);
            cache.addResult(additional);
        }
        return dnsResponse;
    }

    /**
     * Decodes the Question from a DNS response
     * 
     * @param inputStream DataInputStream of the response reader
     * @param response DNS response as a byte array 
     */
    private static void readQuestion(DataInputStream inputStream, byte[] response) throws IOException {
        String domain = readMessage(inputStream, response);
        int qtype = inputStream.readUnsignedShort();
        int qclass = inputStream.readUnsignedShort();
    }

    /**
     * Decodes QNAME, NAME, RData while handling pointers 
     * 
     * @param inputStream DataInputStream of the response reader
     * @param response DNS response as a byte array
     * 
     * @return String of the domain name
     */
    private static String readMessage(DataInputStream inputStream, byte[] response) throws IOException {
        DataInputStream tempInputStream = inputStream;
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        DataOutputStream dataOutPutStream = new DataOutputStream(byteArrayOutputStream);

        while (true) {
            int byteValue = tempInputStream.readUnsignedByte();
            if (byteValue == 0) {
                break;
            }

            if ((byteValue >> 6) == 0b11) { // first two bits are ones indicates pointer
                int offset = ((byteValue & 0b111111) << 8) + tempInputStream.readUnsignedByte(); // next 14 bits is the offset
                tempInputStream = new DataInputStream(
                        new ByteArrayInputStream(Arrays.copyOfRange(response, offset, response.length)));
            } else {
                while (byteValue-- > 0) {
                    dataOutPutStream.writeByte(tempInputStream.readUnsignedByte());
                }
                dataOutPutStream.writeByte(ASCII_PERIOD);
            }
        }

        String domain = byteArrayOutputStream.toString();
        if (domain.length() > 0) {
            domain = domain.substring(0, domain.length() - 1); // remove extra period added at the end
        }
        return domain;
    }

    /**
     * Decodes the Response Record and caches the relevant information 
     * 
     * @param inputStream DataInputStream of the response reader
     * @param response DNS response as a byte array 
     * 
     * @return returns a ResourceRecord
     */
    private static ResourceRecord readResourceRecord(DataInputStream inputStream, byte[] response) throws IOException {
        ResourceRecord resourceRecord;

        String domain = readMessage(inputStream, response);
        int typeField = inputStream.readShort();
        int classField = inputStream.readShort();
        int ttl = inputStream.readInt();
        int rdLength = inputStream.readShort();

        if (typeField == RecordType.A.getCode() || typeField == RecordType.AAAA.getCode()) {
            byte[] addr = new byte[rdLength];
            for (int i = 0; i < rdLength; i++) {
                addr[i] = inputStream.readByte();
            }
            resourceRecord = new ResourceRecord(domain, RecordType.getByCode(typeField), ttl, InetAddress.getByAddress(addr));
        } else if (typeField == RecordType.SOA.getCode()) {
            resourceRecord = new ResourceRecord(domain, RecordType.getByCode(typeField), ttl, "----");
        } else { // typeField == RecordType.NS.getCode()
            String result = readMessage(inputStream, response);
            resourceRecord = new ResourceRecord(domain, RecordType.getByCode(typeField), ttl, result);
        }
        return resourceRecord;
    }

    /**
     * Prints the result of a DNS query.
     *
     * @param node    Host name and record type used for the query.
     * @param results Set of results to be printed for the node.
     */
    private static void printResults(DNSNode node, Set<ResourceRecord> results) {
        if (results.isEmpty())
            System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                    node.getType(), -1, "0.0.0.0");
        for (ResourceRecord record : results) {
            System.out.printf("%-30s %-5s %-8d %s\n", node.getHostName(),
                    node.getType(), record.getTTL(), record.getTextResult());
        }
    }

    /**
     * Prints out the DNS Response.
     *
     * @param dnsResponse 
     */
    private static void printDNSResponse(DNSResponse dnsResponse) {
        List<ResourceRecord> answers = dnsResponse.getAnswerRecords();
        List<ResourceRecord> authorities = dnsResponse.getAuthorityRecords();
        List<ResourceRecord> additional = dnsResponse.getAdditionalRecords();

        System.out.println("Response ID: " + dnsResponse.getQueryID() + " Authoritative = " + dnsResponse.getAuthoritative());
        System.out.println("  Answers (" + answers.size() + ")");
        for (ResourceRecord record : answers) {
            verbosePrintResourceRecord(record, record.getType().getCode());
        }
        System.out.println("  Nameservers (" + authorities.size() + ")");
        for (ResourceRecord record : authorities) {
            verbosePrintResourceRecord(record, record.getType().getCode());
        }
        System.out.println("  Additional Information (" + additional.size() + ")");
        for (ResourceRecord record : additional) {
            verbosePrintResourceRecord(record, record.getType().getCode());
        }
    }

    /**
     * Prints the fields for the resource record.
     *
     * @param record  ResourceRecord to be printed.
     * @param rtype   Type code of the resource record.
     */
    private static void verbosePrintResourceRecord(ResourceRecord record, int rtype) {
        if (verboseTracing)
            System.out.format("       %-30s %-10d %-4s %s\n", record.getHostName(),
                    record.getTTL(),
                    record.getType() == RecordType.OTHER ? rtype : record.getType(),
                    record.getTextResult());
    }

    /**
     * Checks for error codes within the Rcode of a DNS response. And throws a DNSException
     * when the rcode indicates that there is an error.
     *
     * @param dnsResponse 
     */
    private static void checkRCodeErrors(DNSResponse dnsResponse) throws DNSException {
        if (dnsResponse.getRCode() == 5) {
            throw new DNSException("rCode == 5");
        } else if (dnsResponse.getRCode() == 3) {
            throw new DNSException("rCode == 3");
        } else if (dnsResponse.getAuthoritative() && dnsResponse.getRCode() == 0
                && dnsResponse.getAnswerRecords().size() == 0) {
            throw new DNSException("authoritative reponse returned but there is no answer");
        }
    }
}