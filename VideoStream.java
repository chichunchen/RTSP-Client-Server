//VideoStream

import java.io.*;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedList;


public class VideoStream {

    private FileInputStream fis; // current video file
    private LinkedList<FileInputStream> fislist;
    private int frame_nb; //current frame nb

    //-----------------------------------
    //constructor for a single video segment
    //-----------------------------------
    public VideoStream(String filename) throws Exception {

        //init variables
        fis = new FileInputStream(filename);
        frame_nb = 0;
    }

    //-----------------------------------
    //constructor for multiple video segment with folder and file name
    //-----------------------------------
    public VideoStream(File dir) throws Exception {
        fislist = new LinkedList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                FileInputStream newfis = new FileInputStream(Paths.get(dir.getName(), file.getName()).toString());
                fislist.add(newfis);
            }
        }
        fis = fislist.removeFirst();
        frame_nb = 0;
    }

    //-----------------------------------
    // getnextframe
    //returns the next frame as an array of byte and the size of the frame
    //-----------------------------------
    public int getnextframe(byte[] frame) throws Exception {
        int length = 0;
        String length_string;
        byte[] frame_length = new byte[5];

        //read current frame length
        int l = fis.read(frame_length, 0, 5);
        System.out.println("[DEBUG] l = " + l);

        //transform frame_length to integer
        length_string = new String(frame_length);
        length = Integer.parseInt(length_string);

        return (fis.read(frame, 0, length));
    }

    /**
     * Indicate whether all the video segments has been transmitted
     * @return true if video list is empty, otherwise, false
     */
    public boolean isFinished() {
        return fislist.isEmpty();
    }

    public void updateSegment() {
        fis = fislist.removeFirst();
    }
}