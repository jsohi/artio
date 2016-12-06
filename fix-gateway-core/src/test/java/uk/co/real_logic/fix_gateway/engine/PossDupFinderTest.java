package uk.co.real_logic.fix_gateway.engine;

import org.agrona.concurrent.UnsafeBuffer;
import org.junit.Before;
import org.junit.Test;
import uk.co.real_logic.fix_gateway.dictionary.IntDictionary;
import uk.co.real_logic.fix_gateway.otf.OtfParser;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertEquals;

public class PossDupFinderTest
{
    private static final byte[] FIRST_MESSAGE =
        ("8=FIX.4.4\0019=0065\00135=5\00149=initiator\00156=acceptor\00134=2\001" +
            "52=20161206-11:04:51.461\00110=088\001").getBytes(US_ASCII);

    private static final byte[] SECOND_MESSAGE =
        ("8=FIX.4.4\0019=0065\00135=5\00149=initiator\00156=acceptor\00134=2\001" +
            "52=20161206-11:04:51.461\00143=Y\00110=088\001").getBytes(US_ASCII);

    private final PossDupFinder possDupFinder = new PossDupFinder();
    private final OtfParser parser = new OtfParser(possDupFinder, new IntDictionary());
    private final UnsafeBuffer buffer = new UnsafeBuffer(new byte[FIRST_MESSAGE.length + SECOND_MESSAGE.length]);

    @Before
    public void setUp()
    {
        buffer.putBytes(0, FIRST_MESSAGE);
        buffer.putBytes(FIRST_MESSAGE.length, SECOND_MESSAGE);
    }

    @Test
    public void shouldOnlyReturnPossDupAtLength()
    {
        parser.onMessage(buffer, 0, FIRST_MESSAGE.length);

        assertEquals(PossDupFinder.NO_ENTRY, possDupFinder.possDupOffset());
    }
}
