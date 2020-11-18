import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.sound.sampled.*;

public class Transmission {

    public static InetAddress hostIP;
    public static int serverPort = 38002;
    public static int clientPort = 48002;
    public static int TCPPort = 38078;
    public static DatagramSocket UDPout;
    public static DatagramSocket UDPin;

    //Utils
    public static final byte[] jpgStart = new byte[]{(byte)0xFF,(byte)0xD8};
    public static final byte[] jpgEnd = new byte[]{(byte)0xFF,(byte)0xD9};
    public static final String date = (new SimpleDateFormat("MM-dd hh")).format(new Date());
    public static int lastHigh = 0;

    static {
        try {
            UDPout = new DatagramSocket();
            UDPin = new DatagramSocket(clientPort);
            hostIP = InetAddress.getByAddress(new byte[]{(byte) 155, (byte) 207, (byte) 18, (byte) 208});
            UDPout.setSoTimeout(1000);
        } catch (SocketException e) {
            e.printStackTrace();
        } catch (UnknownHostException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void echo(String msg, int repeats){
        try{
            UDPin.setSoTimeout(500);

            File echo_file = new File("results/echo-"+date+".txt");
            if(!echo_file.createNewFile()){
                echo_file.delete();
                echo_file.createNewFile();
            }
            FileWriter fw = new FileWriter(echo_file);

            DatagramPacket inPacket = createBufferedPacket(512);

            for(int i = 0; i < repeats;i++){
                try {
                    sendPacket(msg);
                    long start_time = System.currentTimeMillis();
                    UDPin.receive(inPacket);
                    long end_time = System.currentTimeMillis();
                    String message = new String(inPacket.getData(), 0, inPacket.getLength());
                    fw.append(message+"\tDelay: "+(end_time-start_time)+"ms\n");
                } catch (Exception e){
                    System.err.println("Receive timeout on echo request "+i);
                }
            }

            fw.close();
            if(echo_file.length()==0){
                echo_file.delete();
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    private static void image(String msg){
        image(msg,128);
    }

    private static void image(String msg, int packetSize){
        msg += "L="+packetSize;
        System.out.println("Image Started");
        try{
            UDPin.setSoTimeout(3000);

            Vector<Byte> imageData = new Vector<>();
            DatagramPacket inPacket = createBufferedPacket(packetSize);

            File img = new File("results/img"+date+".jpg");
            if(!img.createNewFile()){
                img.delete();
                img.createNewFile();
            }

            sendPacket(msg);
            for(;;){
                try {
                    UDPin.receive(inPacket);
                    sendPacket("NEXT");
                    for (Byte b : inPacket.getData()) {
                        imageData.add(b);
                    }
                } catch (Exception e){
                    break;
                }
            }

            byte[] data = new byte[imageData.size()];
            for(int i = 0; i < imageData.size(); i++){
                data[i] = imageData.get(i);
            }

            int start = delim_search(jpgStart,data);
            int end = delim_search(jpgEnd,data);
            ByteArrayInputStream bis = new ByteArrayInputStream(Arrays.copyOfRange(data,start,end));
            BufferedImage image = ImageIO.read(bis);

            if(!(image==null)){
                ImageIO.write(image,"jpg",img);
                System.out.println(("Image size: " + img.length()));
            }
        } catch (Exception e){
            System.err.println(e.getMessage());
        }
    }

    private static void sound(String msg) {
        try{
            System.out.println("Started Music");
            boolean AQ = msg.contains("AQ");
            UDPin.setSoTimeout(3000);

            DatagramPacket inPacket = createBufferedPacket(AQ?132:128);
            Queue<byte[]> audioBuffer = new LinkedList<>();
            Vector<Byte> audioData = new Vector<>();

            File sound = new File("results/sound"+date+".wav");
            if(!sound.createNewFile()){
                sound.delete();
                sound.createNewFile();
            }

            AudioFormat format = new AudioFormat(8000,AQ?16:8,1,true,AQ?true:false);

            Thread receiveThread = new Thread(){
                byte [] data = new byte[1];
                public void run(){
                    //System.out.println("Receive started");
                    for(;;){
                        try {
                            UDPin.receive(inPacket);
                            if(AQ) data = AQDPCM(inPacket.getData());
                            else data = DPCM(inPacket.getData());
                            audioBuffer.add(data);
                            for(byte b: data) audioData.add(b);
                        } catch (Exception e) {
                            break;
                        }
                    }

                    //System.out.println("Receive ended");
                    try{
                        data = new byte[audioData.size()];
                        for(int i = 0; i<data.length;i++) data[i]=audioData.get(i);
                        ByteArrayInputStream bis = new ByteArrayInputStream(data);
                        AudioInputStream ais = new AudioInputStream(bis, format, data.length);
                        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, sound);
                    } catch (Exception e){}
                }
            };

            Thread listenThread = new Thread(){
                SourceDataLine lineOut = AudioSystem.getSourceDataLine(format);

                public void run() {
                    System.out.println("Listening");
                    try {
                        lineOut.open(format, 1024);
                        lineOut.start();
                        boolean flag = true;
                        while(true) {
                            long time = System.currentTimeMillis();
                            long dur = System.currentTimeMillis()-time;
                            while((audioBuffer.size()<1)&&(dur< UDPout.getSoTimeout()+1000)){
                                dur = System.currentTimeMillis()-time;
                            }
                            try {
                                if(flag){
                                    flag = false;
                                    //System.out.println("First note heard");
                                }
                                lineOut.write(audioBuffer.remove(), 0, 256);
                            } catch (Exception e) {
                                //System.err.println("Listen stopped\nCause: " + e.getLocalizedMessage());
                                break;
                            }
                        }
                        lineOut.drain();
                        lineOut.close();
                    } catch (Exception e) {
                        System.err.println("Other error: " + e.getMessage());
                    }
                }
            };
            sendPacket(msg);

            receiveThread.start();
            listenThread.start();
            receiveThread.join();
            listenThread.join();

            System.out.println("Data downloaded");

        } catch (Exception e){
        }
    }

    private static void helloGoogle(){

        try{
            Socket sock = new Socket(hostIP,80);
            OutputStream os = sock.getOutputStream();
            InputStream is = sock.getInputStream();

            os.write("GET /index.html HTTP/1.0\r\n\r\n".getBytes());
            int a = 0;
            String message = "";
            while((a=is.read())!=-1){
                message+=(char)a;
            }
            System.out.print(message);

        } catch(Exception e){

        }

    }

    private static void telemetry(String msg){
        try{
            UDPin.setSoTimeout(2000);

            /*File telemetry_file = new File("results/copter-"+date+".txt");
            if(!telemetry_file.createNewFile()){
                telemetry_file.delete();
                telemetry_file.createNewFile();
            }
            FileWriter fw = new FileWriter(telemetry_file);

            DatagramPacket inPacket = createBufferedPacket(512);*/

            Socket TCPsock = new Socket(hostIP, 37078);
            InputStream TCPis = TCPsock.getInputStream();
            OutputStream TCPos = TCPsock.getOutputStream();
            TCPos.write("AUTO FLIGHTLEVEL=068 LMOTOR=160 RMOTOR=160 PILOT\r\n\r\n".getBytes());
            int a = -1;
            String message = "";
            for(;;){
                a = TCPis.read();
                if(a==-1) break;
                message += (char)a;
                System.out.print((char)a);
            }
            System.out.print(message+"\n");

            /*try {
                String packetInfo = msg;
                byte[] packetBytes = packetInfo.getBytes();
                DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, hostIP, 37078);
                UDPout.send(packet);
                long start_time = System.currentTimeMillis();
                UDPin.receive(inPacket);
                long end_time = System.currentTimeMillis();
                message = new String(inPacket.getData(), 0, inPacket.getLength());
                System.out.println("Telemetry: "+message);
                fw.append(message+"\tDelay: "+(end_time-start_time)+"ms\n");
            } catch (Exception e){
                System.err.println("Telemetry receive timeout");
            }*/

            TCPsock.close();

            /*fw.close();
            if(telemetry_file.length()==0){
                telemetry_file.delete();
            }*/
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    private static void vehicle(String msg){
        try{
            UDPin.setSoTimeout(1000);

            File vehicle_file = new File("results/vehicle-"+date+".txt");
            if(!vehicle_file.createNewFile()){
                vehicle_file.delete();
                vehicle_file.createNewFile();
            }
            FileWriter fw = new FileWriter(vehicle_file);

            DatagramPacket inPacket = createBufferedPacket(512);

            try {
                sendPacket(msg);
                long start_time = System.currentTimeMillis();
                UDPin.receive(inPacket);
                long end_time = System.currentTimeMillis();
                String message = new String(inPacket.getData(), 0, inPacket.getLength());
                System.out.println("Vehicle diagnosis: "+message);
                fw.append(message+"\tDelay: "+(end_time-start_time)+"ms\n");
            } catch (Exception e){
                System.err.println("Vehicle diagnosis receive timeout");
            }

            fw.close();
            if(vehicle_file.length()==0){
                vehicle_file.delete();
            }
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    public static void main(String[] args) {
        echo("E0573T00", 5);
        //image("M5239FLOW=ON");
        //sound("A9731AQL17F512");
        //telemetry("Q8853");
        //vehicle("V4704OBD=01 1F");
        //helloGoogle();
    }

    private static int delim_search(byte[] delim, byte[] data){
        int res = -1;
        for(int i = 0; i < data.length-1; i++) {
            if (delim[0] == data[i] && delim[1] == data[i + 1]) {
                res = i;
            }
        }
        return res;
    }

    private static void sendPacket(String msg){
        try {
            String packetInfo = msg;
            byte[] packetBytes = packetInfo.getBytes();
            DatagramPacket packet = new DatagramPacket(packetBytes, packetBytes.length, hostIP, serverPort);
            UDPout.send(packet);
        } catch (Exception e){

        }
    }

    private static DatagramPacket createBufferedPacket(int bufferSize){
        byte[] buffer = new byte[bufferSize];
        return new DatagramPacket(buffer,bufferSize);
    }

    private static byte[] DPCM(byte[] data) {
        byte[] res = new byte[2*data.length];
        int low = 0;
        int high = lastHigh;

        for (int i = 0; i < data.length;i++) {
            low = (((int)data[i] & 0x0F) - 8)*2 + high;
            high = ((((int)data[i] & 0xF0)>>4) - 8)*2 + low;
            res[2*i] = (byte)high;
            res[2*i+1] = (byte)low;
        }
        lastHigh = high;

        return res;
    }

    private static byte[] AQDPCM(byte[] data) {
        int m = (((data[1]&0xFF)<<8) | (data[0]&0xFF));
        int b = (((data[3]&0xFF)<<8) | (data[2]&0xFF));
        if (m>32767) m -= 65536;

        data = Arrays.copyOfRange(data,4,data.length);
        short[] res = new short[2*data.length];
        int low = 0;
        int high = lastHigh;

        for (int i = 0; i < data.length;i++) {
            low = (((int)data[i] & 0x0F) - 8)*b + high + m;
            high = ((((int)data[i] & 0xF0)>>4) - 8)*b + low + m;
            res[2*i] = (high>32767)?(32767):((high<-32768)?(-32768):((short)high));
            res[2*i+1] = (low>32767)?(32767):((low<-32768)?(-32768):((short)low));
        }
        lastHigh = high;

        ByteBuffer bb = ByteBuffer.allocate(res.length*2);
        ShortBuffer sb = bb.asShortBuffer().put(res);
        return bb.array();
    }
}