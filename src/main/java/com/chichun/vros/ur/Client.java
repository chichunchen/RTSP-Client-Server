package com.chichun.vros.ur;

import org.jcodec.api.FrameGrab;
import org.jcodec.api.JCodecException;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Picture;
import org.jcodec.scale.AWTUtil;

import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.util.*;
import java.text.*;
import java.awt.*;
import java.awt.event.*;
import javax.swing.*;
import javax.swing.Timer;

/**
 * Client
 * usage: java Client [Server hostname] [Server RTSP listening port] [Video file requested]
 */

public class Client {
    //GUI
    //----
    private JFrame f = new JFrame("VROS Client");
    private JButton setupButton = new JButton("Setup");
    private JButton playButton = new JButton("Play");
    private JButton pauseButton = new JButton("Pause");
    private JButton tearButton;
    private JButton describeButton = new JButton("Session");
    private JPanel mainPanel = new JPanel();
    private JPanel buttonPanel = new JPanel();
    private JLabel statLabel1 = new JLabel();
    private JLabel statLabel2 = new JLabel();
    private JLabel statLabel3 = new JLabel();
    private JLabel iconLabel = new JLabel();
    private ImageIcon icon;

    //RTP variables:
    //----------------
    private DatagramPacket rcvdp;            //UDP packet received from the server
    private DatagramSocket RTPsocket;        //socket to be used to send and receive UDP packets
    private static int RTP_RCV_PORT = 25000; //port where the client will receive the RTP packets

    private Timer timer; //timer used to receive data from the UDP socket
    private byte[] buf;  //buffer used to store data received from the server

    //RTSP variables
    //----------------
    //rtsp states
    private final static int INIT = 0;
    private final static int READY = 1;
    private final static int PLAYING = 2;
    private static int state;            //RTSP state == INIT or READY or PLAYING
    private Socket RTSPsocket;           //socket used to send/receive RTSP messages
    private InetAddress ServerIPAddr;

    //input and output stream filters
    private static BufferedReader RTSPBufferedReader;
    private static BufferedWriter RTSPBufferedWriter;
    private static String VideoFolderName; //video file to request to the server
    private int RTSPSeqNb = 0;           //Sequence number of RTSP messages within the session
    private String RTSPid;              // ID of the RTSP session (given by the RTSP Server)

    private final static String CRLF = "\r\n";
    private final static String DES_FNAME = "session_info.txt";

    //RTCP variables
    //----------------
    private DatagramSocket RTCPsocket;          //UDP socket for sending RTCP packets
    private static int RTCP_RCV_PORT = 19001;   //port where the client will receive the RTP packets
    private static int RTCP_PERIOD = 400;       //How often to send RTCP packets
    private RtcpSender rtcpSender;

    //Video constants:
    //------------------
    static int MJPEG_TYPE = 26; //RTP payload type for MJPEG video

    //Statistics variables:
    //------------------
    private double statDataRate;        //Rate of video data received in bytes/s
    private int statTotalBytes;         //Total number of bytes received in a session
    private double statStartTime;       //Time in milliseconds when start is pressed
    private double statTotalPlayTime;   //Time in milliseconds of video playing since beginning
    private float statFractionLost;     //Fraction of RTP data packets from sender lost since the prev packet was sent
    private int statCumLost;            //Number of packets lost
    private int statExpRtpNb;           //Expected Sequence number of RTP messages within the session
    private int statHighSeqNb;          //Highest sequence number received in session

    private FrameSynchronizer fsynch;

    //--------------------------
    //Constructor
    //--------------------------
    public Client() {

        //build GUI
        //--------------------------

        //Frame
        f.addWindowListener(new WindowAdapter() {
            public void windowClosing(WindowEvent e) {
                System.exit(0);
            }
        });

        //Buttons
        buttonPanel.setLayout(new GridLayout(1, 0));
        buttonPanel.add(setupButton);
        buttonPanel.add(playButton);
        buttonPanel.add(pauseButton);
        tearButton = new JButton("Close");
        buttonPanel.add(tearButton);
        buttonPanel.add(describeButton);
        setupButton.addActionListener(new setupButtonListener());
        playButton.addActionListener(new playButtonListener());
        pauseButton.addActionListener(new pauseButtonListener());
        tearButton.addActionListener(new tearButtonListener());
        describeButton.addActionListener(new describeButtonListener());

        //Statistics
        statLabel1.setText("Total Bytes Received: 0");
        statLabel2.setText("Packets Lost: 0");
        statLabel3.setText("Data Rate (bytes/sec): 0");

        //Image display label
        iconLabel.setIcon(null);

        //frame layout
        mainPanel.setLayout(null);
        mainPanel.add(iconLabel);
        mainPanel.add(buttonPanel);
        mainPanel.add(statLabel1);
        mainPanel.add(statLabel2);
        mainPanel.add(statLabel3);
        iconLabel.setBounds(0, 0, 1280, 600);
        buttonPanel.setBounds(0, 600, 380, 40);
        statLabel1.setBounds(0, 640, 380, 20);
        statLabel2.setBounds(0, 660, 380, 20);
        statLabel3.setBounds(0, 680, 380, 20);

        f.getContentPane().add(mainPanel, BorderLayout.CENTER);
        f.setSize(new Dimension(1280, 720));
        f.setVisible(true);

        //init timer
        //--------------------------
        timer = new Timer(5, new timerListener());
        timer.setInitialDelay(0);
        timer.setCoalesce(true);

        //init RTCP packet sender
        rtcpSender = new RtcpSender(400);

        //allocate enough memory for the buffer used to receive data from the server
        buf = new byte[15000];

        //create the frame synchronizer
        fsynch = new FrameSynchronizer(100);
    }

    /**
     * Main Function.
     *
     * @param argv
     * @throws Exception
     */
    public static void main(String argv[]) throws Exception {
        //Create a Client object
        Client theClient = new Client();

        //get server RTSP port and IP address from the command line
        //------------------
        int RTSP_server_port = Integer.parseInt(argv[1]);
        String ServerHost = argv[0];
        theClient.ServerIPAddr = InetAddress.getByName(ServerHost);

        //get video filename to request:
        VideoFolderName = argv[2];

        //Establish a TCP connection with the server to exchange RTSP messages
        //------------------
        theClient.RTSPsocket = new Socket(theClient.ServerIPAddr, RTSP_server_port);

        //Establish a UDP connection with the server to exchange RTCP control packets
        //------------------

        //Set input and output stream filters:
        RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()));
        RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()));

        //init RTSP state:
        state = INIT;
    }


    //------------------------------------
    //Handler for buttons
    //------------------------------------

    Vector<Picture> picvec;

    /**
     * Handler for Setup button.
     */
    class setupButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            // try decode from storage
            String base = "storage/rhino/output_";
            picvec = new Vector<>();
            boolean odd = false;
            for (int i = 1; i < 50; i++) {
                String filename = base + Integer.toString(i);
                File file = new File(filename + ".mp4");
                FrameGrab grab = null;
                try {
                    grab = FrameGrab.createFrameGrab(NIOUtils.readableChannel(file));
                    Picture picture;
                    while (null != (picture = grab.getNativeFrame())) {
                        System.out.println(picture.getWidth() + "x" + picture.getHeight() + " " + picture.getColor());
                        picvec.add(picture);
                    }
                } catch (JCodecException je1) {
                    je1.printStackTrace();
                } catch (IOException e1) {
                    e1.printStackTrace();
                }
            }

            System.out.println("Setup Button pressed !");
            if (state == INIT) {
                // Init non-blocking RTPsocket that will be used to receive data
                try {
                    // construct a new DatagramSocket to receive RTP packets from the server, on port RTP_RCV_PORT
                    RTPsocket = new DatagramSocket(RTP_RCV_PORT);
                    // UDP socket for sending QoS RTCP packets
                    RTCPsocket = new DatagramSocket();
                    // set TimeOut value of the socket to 5msec.
                    RTPsocket.setSoTimeout(5);
                } catch (SocketException se) {
                    System.out.println("Socket exception: " + se);
                    System.exit(0);
                }

                // init RTSP sequence number
                RTSPSeqNb = 1;

                // Send SETUP message to the server
                sendRequest("SETUP");

                // Wait for the response
                if (parseServerResponse() != 200)
                    System.out.println("Invalid Server Response");
                else {
                    //change RTSP state and print new state
                    state = READY;
                    System.out.println("New RTSP state: READY");
                }
            }
            //else if state != INIT then do nothing
        }
    }

    /**
     * Handler for Play button.
     */
    class playButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            System.out.println("Play Button pressed!");

            // Start to save the time in stats
            statStartTime = System.currentTimeMillis();

            if (state == READY) {
                // increase RTSP sequence number
                RTSPSeqNb++;

                // Send PLAY message to the server
                sendRequest("PLAY");

                // Wait for the response
                if (parseServerResponse() != 200) {
                    System.out.println("Invalid Server Response");
                } else {
                    // change RTSP state and print out new state
                    state = PLAYING;
                    System.out.println("New RTSP state: PLAYING");

                    // start the timer
                    timer.start();
                    rtcpSender.startSend();
                }
            }
            //else if state != READY then do nothing
        }
    }

    /**
     * Handler for Pause button.
     */
    class pauseButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            System.out.println("Pause Button pressed!");

            if (state == PLAYING) {
                //increase RTSP sequence number
                RTSPSeqNb++;

                //Send PAUSE message to the server
                sendRequest("PAUSE");

                //Wait for the response
                if (parseServerResponse() != 200)
                    System.out.println("Invalid Server Response");
                else {
                    //change RTSP state and print out new state
                    state = READY;
                    System.out.println("New RTSP state: READY");

                    //stop the timer
                    timer.stop();
                    rtcpSender.stopSend();
                }
            }
            //else if state != PLAYING then do nothing
        }
    }

    /**
     * Handler for Teardown button.
     */
    class tearButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {

            System.out.println("Teardown Button pressed !");

            //increase RTSP sequence number
            RTSPSeqNb++;

            //Send TEARDOWN message to the server
            sendRequest("TEARDOWN");

            //Wait for the response
            if (parseServerResponse() != 200)
                System.out.println("Invalid Server Response");
            else {
                //change RTSP state and print out new state
                state = INIT;
                System.out.println("New RTSP state: INIT");

                //stop the timer
                timer.stop();
                rtcpSender.stopSend();

                //exit
                System.exit(0);
            }
        }
    }

    /**
     * Get information about the data stream.
     */
    class describeButtonListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {
            System.out.println("Sending DESCRIBE request");

            // increase RTSP sequence number
            RTSPSeqNb++;

            // Send DESCRIBE message to the server
            sendRequest("DESCRIBE");

            // Wait for the response
            if (parseServerResponse() != 200) {
                System.out.println("Invalid Server Response");
            } else {
                System.out.println("Received response for DESCRIBE");
            }
        }
    }

    /**
     * Handler for timer. Start happens when the play button is pushed, while stop when the pause and close button
     * has been pushed.
     */
    int nb = 0;
    class timerListener implements ActionListener {

        public void actionPerformed(ActionEvent e) {
            System.out.println(nb + ", " + picvec.size());
            if (picvec.size() > nb) {
                BufferedImage bufferedImage = AWTUtil.toBufferedImage(picvec.get(nb));
                icon = new ImageIcon(bufferedImage);
                System.out.println(icon.toString());
                iconLabel.setIcon(icon);
                nb++;
            }

//            // Construct a DatagramPacket to receive data from the UDP socket
//            rcvdp = new DatagramPacket(buf, buf.length);
//\
//            // TODO receive encoded video segments from server, should then decode and render the images on the panel
//            try {
//                // receive the DP from the socket, save time for stats
//                RTPsocket.receive(rcvdp);
//
//                double curTime = System.currentTimeMillis();
//                statTotalPlayTime += curTime - statStartTime;
//                statStartTime = curTime;
//
//                // create an RTPpacket object from the DP
//                RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
//                int highestSeqNb = rtp_packet.getsequencenumber();
//
//                // print important header fields of the RTP packet received:
//                System.out.println("Got RTP packet with SeqNum # " + highestSeqNb
//                        + " TimeStamp " + rtp_packet.gettimestamp() + " ms, of type "
//                        + rtp_packet.getpayloadtype());
//
//                // print header bitstream:
//                rtp_packet.printheader();
//
//                // get the payload bitstream from the RTPpacket object
//                int payload_length = rtp_packet.getpayload_length();
//                byte[] payload = new byte[payload_length];
//                rtp_packet.getpayload(payload);
//
//                // compute stats and update the label in GUI
//                statExpRtpNb++;
//                if (highestSeqNb > statHighSeqNb) {
//                    statHighSeqNb = highestSeqNb;
//                }
//                if (statExpRtpNb != highestSeqNb) {
//                    statCumLost++;
//                }
//                statDataRate = statTotalPlayTime == 0 ? 0 : (statTotalBytes / (statTotalPlayTime / 1000.0));
//                statFractionLost = (float) statCumLost / statHighSeqNb;
//                statTotalBytes += payload_length;
//                updateStatsLabel();
//
//                // TODO decode received video segment
//                // get an Image object from the payload bitstream
//                Toolkit toolkit = Toolkit.getDefaultToolkit();
//                fsynch.addFrame(toolkit.createImage(payload, 0, payload_length), highestSeqNb);
//                // display the image as an ImageIcon object
//                icon = new ImageIcon(fsynch.nextFrame());
//                iconLabel.setIcon(icon);
//            } catch (InterruptedIOException iioe) {
//                System.out.println("Nothing to read");
//            } catch (IOException ioe) {
//                System.out.println("Exception caught: " + ioe);
//            }
        }
    }

    //------------------------------------
    // Send RTCP control packets for QoS feedback
    //------------------------------------
    class RtcpSender implements ActionListener {

        private Timer rtcpTimer;
        int interval;

        // Stats variables
        private int numPktsExpected;    // Number of RTP packets expected since the last RTCP packet
        private int numPktsLost;        // Number of RTP packets lost since the last RTCP packet
        private int lastHighSeqNb;      // The last highest Seq number received
        private int lastCumLost;        // The last cumulative packets lost
        private float lastFractionLost; // The last fraction lost

        Random randomGenerator;         // For testing only

        public RtcpSender(int interval) {
            this.interval = interval;
            rtcpTimer = new Timer(interval, this);
            rtcpTimer.setInitialDelay(0);
            rtcpTimer.setCoalesce(true);
            randomGenerator = new Random();
        }

        public void run() {
            System.out.println("RtcpSender Thread Running");
        }

        public void actionPerformed(ActionEvent e) {

            // Calculate the stats for this period
            numPktsExpected = statHighSeqNb - lastHighSeqNb;
            numPktsLost = statCumLost - lastCumLost;
            lastFractionLost = numPktsExpected == 0 ? 0f : (float) numPktsLost / numPktsExpected;
            lastHighSeqNb = statHighSeqNb;
            lastCumLost = statCumLost;

            //To test lost feedback on lost packets
            // lastFractionLost = randomGenerator.nextInt(10)/10.0f;

            RTCPpacket rtcp_packet = new RTCPpacket(lastFractionLost, statCumLost, statHighSeqNb);
            int packet_length = rtcp_packet.getlength();
            byte[] packet_bits = new byte[packet_length];
            rtcp_packet.getpacket(packet_bits);

            try {
                DatagramPacket dp = new DatagramPacket(packet_bits, packet_length, ServerIPAddr, RTCP_RCV_PORT);
                RTCPsocket.send(dp);
            } catch (InterruptedIOException iioe) {
                System.out.println("Nothing to read");
            } catch (IOException ioe) {
                System.out.println("Exception caught: " + ioe);
            }
        }

        // Start sending RTCP packets
        public void startSend() {
            rtcpTimer.start();
        }

        // Stop sending RTCP packets
        public void stopSend() {
            rtcpTimer.stop();
        }
    }

    //------------------------------------
    //Synchronize frames
    //------------------------------------
    class FrameSynchronizer {

        private ArrayDeque<Image> queue;
        private int bufSize;
        private int curSeqNb;
        private Image lastImage;

        public FrameSynchronizer(int bsize) {
            curSeqNb = 1;
            bufSize = bsize;
            queue = new ArrayDeque<>(bufSize);
        }

        /**
         * Synchronize frames based on their sequence number.
         *
         * @param image
         * @param seqNum
         */
        public void addFrame(Image image, int seqNum) {
            if (seqNum < curSeqNb) {
                queue.add(lastImage);
            } else if (seqNum > curSeqNb) {
                for (int i = curSeqNb; i < seqNum; i++) {
                    queue.add(lastImage);
                }
                queue.add(image);
            } else {
                queue.add(image);
            }
        }

        /**
         * Get the next synchronized frame.
         *
         * @return
         */
        public Image nextFrame() {
            curSeqNb++;
            lastImage = queue.peekLast();
            return queue.remove();
        }
    }

    //------------------------------------
    //Parse Server Response
    //------------------------------------
    private int parseServerResponse() {
        int reply_code = 0;

        try {
            //parse status line and extract the reply_code:
            String StatusLine = RTSPBufferedReader.readLine();
            System.out.println("RTSP Client - Received from Server:");
            System.out.println(StatusLine);

            StringTokenizer tokens = new StringTokenizer(StatusLine);
            tokens.nextToken(); //skip over the RTSP version
            reply_code = Integer.parseInt(tokens.nextToken());

            //if reply code is OK get and print the 2 other lines
            if (reply_code == 200) {
                String SeqNumLine = RTSPBufferedReader.readLine();
                System.out.println(SeqNumLine);

                String SessionLine = RTSPBufferedReader.readLine();
                System.out.println(SessionLine);

                tokens = new StringTokenizer(SessionLine);
                String temp = tokens.nextToken();
                //if state == INIT gets the Session Id from the SessionLine
                if (state == INIT && temp.compareTo("Session:") == 0) {
                    RTSPid = tokens.nextToken();
                } else if (temp.compareTo("Content-Base:") == 0) {
                    // Get the DESCRIBE lines
                    String newLine;
                    for (int i = 0; i < 6; i++) {
                        newLine = RTSPBufferedReader.readLine();
                        System.out.println(newLine);
                    }
                }
            }
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }

        return (reply_code);
    }

    private void updateStatsLabel() {
        DecimalFormat formatter = new DecimalFormat("###,###.##");
        statLabel1.setText("Total Bytes Received: " + statTotalBytes);
        statLabel2.setText("Packet Lost Rate: " + formatter.format(statFractionLost));
        statLabel3.setText("Data Rate: " + formatter.format(statDataRate) + " bytes/s");
    }

    //------------------------------------
    //Send RTSP Request
    //------------------------------------

    private void sendRequest(String request_type) {
        try {
            //Use the RTSPBufferedWriter to write to the RTSP socket
            //write the request line:
            RTSPBufferedWriter.write(request_type + " " + VideoFolderName + " RTSP/1.0" + CRLF);

            //write the CSeq line:
            RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);

            //check if request_type is equal to "SETUP" and in this case write the
            //Transport: line advertising to the server the port used to receive
            //the RTP packets RTP_RCV_PORT
            switch (request_type) {
                case "SETUP":
                    RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);
                    break;
                case "DESCRIBE":
                    RTSPBufferedWriter.write("Accept: application/sdp" + CRLF);
                    break;
                default:
                    //otherwise, write the Session line from the RTSPid field
                    RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);
                    break;
            }

            RTSPBufferedWriter.flush();
        } catch (Exception ex) {
            System.out.println("Exception caught: " + ex);
            System.exit(0);
        }
    }
}
