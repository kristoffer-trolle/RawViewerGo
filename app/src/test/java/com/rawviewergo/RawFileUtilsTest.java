package com.rawviewergo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.rawviewergo.RawFileUtils.RawFormat;

import org.junit.Test;

public class RawFileUtilsTest {

    @Test
    public void isRawFile_recognizesAllSupportedExtensions() {
        assertTrue(RawFileUtils.isRawFile("photo.dcr"));
        assertTrue(RawFileUtils.isRawFile("photo.mef"));
        assertTrue(RawFileUtils.isRawFile("photo.iiq"));
        // case-insensitive, and a card reader is likely to give upper-case extensions
        assertTrue(RawFileUtils.isRawFile("CF123558.IIQ"));
        assertTrue(RawFileUtils.isRawFile("4H6B9130.DCR"));
    }

    @Test
    public void isRawFile_rejectsUnsupportedOrMissingExtensions() {
        assertFalse(RawFileUtils.isRawFile("photo.jpg"));
        assertFalse(RawFileUtils.isRawFile("photo"));
        assertFalse(RawFileUtils.isRawFile(null));
    }

    @Test
    public void classify_matchesEachFormat() {
        assertEquals(RawFormat.DCR, RawFileUtils.classify("4H6B9130.DCR"));
        assertEquals(RawFormat.MEF, RawFileUtils.classify("MMFC0399.mef"));
        assertEquals(RawFormat.OTHER, RawFileUtils.classify("CF123558.IIQ"));
        assertEquals(RawFormat.OTHER, RawFileUtils.classify(null));
    }
}
