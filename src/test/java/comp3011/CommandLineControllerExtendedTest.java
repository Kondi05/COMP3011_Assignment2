package comp3011;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended CLI regression tests. Complements {@link CommandLineControllerTest}
 * by covering the short/long form equivalence, options surrounding the video
 * file argument, and repeated-effect stacking 
 */
public class CommandLineControllerExtendedTest {

    @Test
    void longAndShortFormsAreEquivalent() {
        CommandLineController a = new CommandLineController(new String[]{
                "--jitter-frames", "--flicker-frames", "--dust-frames",
                "--dust-frames", "--number-frames", "dummy.mp4"});
        CommandLineController b = new CommandLineController(
                new String[]{"-jfd", "-dn", "dummy.mp4"});
        List<String> expected = List.of("jitter", "flicker", "dust", "dust", "number");
        assertEquals(expected, a.getRequestedFrameProcessors());
        assertEquals(expected, b.getRequestedFrameProcessors());
    }

    @Test
    void videoFileCanAppearBetweenOptions() {
       
        CommandLineController cli = new CommandLineController(
                new String[]{"-j", "dummy.mp4", "-fd"});
        assertEquals(List.of("jitter", "flicker", "dust"), cli.getRequestedFrameProcessors());
    }

    @Test
    void unknownOptionSurfacesAsError() {
        CommandLineController cli = new CommandLineController(
                new String[]{"-z", "dummy.mp4"});
        assertEquals(1, cli.getExitCode());
        assertNotNull(cli.getErrorMessage());
    }
}