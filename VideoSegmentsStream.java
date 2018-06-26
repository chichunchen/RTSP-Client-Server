/*
 * VideoSegmentsStream
 * Currently this class could only be constructed by specifying a folder
 * with video segments in it. The full version is to have multiple fov
 * segments and one full size segment for each time unit.
 *
 * A simple container for the final version could be a vector of vector of
 * (metadata, FileInputStream), which metadata should be the coordination of
 * fov.
 */

import java.io.*;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Vector;


public class VideoSegmentsStream {

    private FileInputStream currentFileInputStream;
    private Vector<FileInputStream> fileInputStreamVector;
    private int currentSegment;

    /**
     * Constructor for multiple video segment with folder and file name.
     * @param dir a file object that represent for a directory
     * @throws FileNotFoundException dir should be a existing directory
     */
    public VideoSegmentsStream(File dir) throws FileNotFoundException {
        fileInputStreamVector = new Vector<>();
        currentSegment = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File file : files) {
                FileInputStream newfis = new FileInputStream(Paths.get(dir.getName(), file.getName()).toString());
                fileInputStreamVector.add(newfis);
            }
        }
        currentFileInputStream = fileInputStreamVector.get(currentSegment);
    }

    /**
     * Getnextframe
     * @param frame a byte stream
     * @return the next frame as an array of byte and the size of the frame
     * @throws Exception
     */
    public int getnextframe(byte[] frame) throws IOException, NumberFormatException {
        int length = 0;
        String length_string;
        byte[] frame_length = new byte[5];

        // read current frame length
        currentFileInputStream.read(frame_length, 0, 5);

        // transform frame_length to integer
        length_string = new String(frame_length);
        length = Integer.parseInt(length_string);

        return (currentFileInputStream.read(frame, 0, length));
    }

    /**
     * Indicate whether all the video segments has been transmitted
     * @return true if video list is empty, otherwise, false
     */
    public boolean isFinished() {
        return fileInputStreamVector.isEmpty();
    }

    /**
     * Update the current video segment for streaming.
     */
    public void updateSegment() {
        currentSegment++;
        currentFileInputStream = fileInputStreamVector.get(currentSegment);
    }
}