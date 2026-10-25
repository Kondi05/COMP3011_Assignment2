package comp3011;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class CommandLineControllerTest {

    @Test
    public void testStackedShortFlags() {
        CommandLineController cli = new CommandLineController(new String[]{"-nsf", "dummy.mp4"});
        List<String> effects = cli.getRequestedFrameProcessors();
        assertEquals(List.of("number", "scratch", "flicker"), effects);
    }

    @Test
    public void testLongFlags() {
        CommandLineController cli = new CommandLineController(new String[]{"--number-frames", "--scratch-frames", "dummy.mp4"});
        List<String> effects = cli.getRequestedFrameProcessors();
        assertEquals(List.of("number", "scratch"), effects);
    }

    @Test
    public void testMixedOrder() {
        CommandLineController cli = new CommandLineController(new String[]{"-jf", "--dust-frames", "-d", "dummy.mp4"});
        List<String> effects = cli.getRequestedFrameProcessors();
        assertEquals(List.of("jitter", "flicker", "dust", "dust"), effects);
    }

    @Test
    public void testUnknownOption() {
        CommandLineController cli = new CommandLineController(new String[]{"-z", "dummy.mp4"});
        assertEquals(1, cli.getExitCode());
        // error message overwritten by missing file check, 
        // So just assert that there is an error (but it is not a specific message)
        assertNotNull(cli.getErrorMessage());
    }
}