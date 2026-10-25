/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}.
 * </p>
 */
public class CommandLineController {
    private final String[] args;

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;
    private final List<String> requestedFrameProcessors = new ArrayList<>();

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }
    
    public List<String> getRequestedFrameProcessors() {
        return requestedFrameProcessors;
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        List<String> videoFiles = new ArrayList<>();
        
        for (String arg : args) {
            // 1. Handle existing core options (exact match)
            if ("-h".equals(arg) || "--help".equals(arg)) {
                helpRequested = true;
            } else if ("-a".equals(arg) || "--audio".equals(arg)) {
                audioRequested = true;
            } else if ("-x".equals(arg) || "--maximise".equals(arg)) {
                maximiseRequested = true;
            } else if ("-1".equals(arg) || "--monitor-1".equals(arg)) {
                setDisplayId(1);
            } else if ("-2".equals(arg) || "--monitor-2".equals(arg)) {
                setDisplayId(2);
            } 
            // 2. Handle long frame processor options (start with --)
            else if (arg.startsWith("--") && isFrameProcessorLongFlag(arg)) {
                requestedFrameProcessors.add(mapLongFlagToName(arg));
            } 
            // 3. Handle stacked/single short frame processor options (start with -)
            else if (arg.startsWith("-") && !arg.startsWith("--") && arg.length() > 1) {
                // treat it as a stack if ALL characters are valid frame processor flags
                // If contains an unknown character (for example -z), treat it as an unknown option
                boolean isAllValid = true;
                for (char c : arg.substring(1).toCharArray()) {
                    if (!isFrameProcessorShortFlag(c)) {
                        isAllValid = false;
                        break;
                    }
                }
                
                if (isAllValid) {
                    // Add each character to the list in order (for example -nsf -> number, scratch, flicker)
                    for (char c : arg.substring(1).toCharArray()) {
                        requestedFrameProcessors.add(mapShortFlagToName(c));
                    }
                } else {
                    errorMessage = "Unknown option: " + arg;
                }
            } 
            // 4. Handle video file (anything that does not start with -)
            else if (!arg.startsWith("-")) {
                videoFiles.add(arg);
            } 
            // 5. Catch any other unrecognized options
            else {
                errorMessage = "Unknown option: " + arg;
            }
        }

        // Final validation 
        if (videoFiles.size() > 1) {
            errorMessage = "Usage: VideoPlayer [options] [video-file]";
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            if (!videoFile.isFile()) {
                errorMessage = "File not found: " + videoFile.getPath();
                videoFile = null;
            }
        } else {
            if (!helpRequested) {
                errorMessage = "No video file specified.";
            }
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            errorMessage = "Only one display option can be used";
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frames  Darken the frame edges");
        System.out.println("  -d, --dust-frames      Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
    
    // Frame Processor Parsing Helpers ->
    // For adding a new frame processor:
    // 1. Add long flag check in isFrameProcessorLongFlag()
    // 2. Add long flag mapping in mapLongFlagToName()
    // 3. Add short flag check in isFrameProcessorShortFlag()
    // 4. Add short flag mapping in mapShortFlagToName()
    // 5. Implement processor in VideoPlayerModel

    private boolean isFrameProcessorLongFlag(String arg) {
        return switch (arg) {
            case "--number-frames", "--scratch-frames", "--flicker-frames",
                 "--black-and-white", "--yellow-frames", "--vignette-frames",
                 "--dust-frames", "--jitter-frames", "--mottle-frames",
                 "--bleed-frames", "--pepper-frames" -> true;
            default -> false;
        };
    }

    private String mapLongFlagToName(String arg) {
        return switch (arg) {
            case "--number-frames" -> "number";
            case "--scratch-frames" -> "scratch";
            case "--flicker-frames" -> "flicker";
            case "--black-and-white" -> "blackandwhite";
            case "--yellow-frames" -> "yellow";
            case "--vignette-frames" -> "vignette";
            case "--dust-frames" -> "dust";
            case "--jitter-frames" -> "jitter";
            case "--mottle-frames" -> "mottle";
            case "--bleed-frames" -> "bleed";
            case "--pepper-frames" -> "pepper";
            default -> throw new IllegalArgumentException("Unknown long flag: " + arg);
        };
    }

    private boolean isFrameProcessorShortFlag(char c) {
        return "nsfwyvdjmbp".indexOf(c) != -1;
    }

    private String mapShortFlagToName(char c) {
        return switch (c) {
            case 'n' -> "number";
            case 's' -> "scratch";
            case 'f' -> "flicker";
            case 'w' -> "blackandwhite";
            case 'y' -> "yellow";
            case 'v' -> "vignette";
            case 'd' -> "dust";
            case 'j' -> "jitter";
            case 'm' -> "mottle";
            case 'b' -> "bleed";
            case 'p' -> "pepper";
            default -> throw new IllegalArgumentException("Unknown short flag: " + c);
        };
    }
}
