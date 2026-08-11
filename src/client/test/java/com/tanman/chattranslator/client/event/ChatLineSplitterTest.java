package com.tanman.chattranslator.client.event;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ChatLineSplitterTest {

    @Test
    void stripsStackedServerDecorationAndSenderName() {
        assertEquals(
                "Слушаю DETEMO и вам советую",
                ChatLineSplitter.split(
                        "[Global] [GOLD] [detemo head]detemo →  Слушаю DETEMO и вам советую").body());
    }

    @Test
    void stripsDecorationWhenThereIsNoChatHeadChunk() {
        assertEquals(
                "pink obby",
                ChatLineSplitter.split("[Global] [Player] LoveInPeace →  pink obby").body());
    }

    @Test
    void stripsVanillaAngleBracketSender() {
        assertEquals("hola amigos", ChatLineSplitter.split("<Alice> hola amigos").body());
    }

    @Test
    void keepsMessageThatMerelyContainsBrackets() {
        assertEquals(
                "ты видел [Global] чат?",
                ChatLineSplitter.split("<Bob> ты видел [Global] чат?").body());
    }

    @Test
    void stripsServerChannelPrefixOnSystemLines() {
        assertEquals(
                "Green Bed was destroyed by Xu_G!",
                ChatLineSplitter.split("BedWars ▸ Green Bed was destroyed by Xu_G!").body());
    }

    @Test
    void leavesPlainSystemLinesAlone() {
        assertEquals(
                "You bought Wool x64",
                ChatLineSplitter.split("You bought Wool x64").body());
    }

    @Test
    void fallsBackToWholeLineWhenStrippingLeavesNothing() {
        assertEquals("[Global] [Player] Steve →", ChatLineSplitter.split("[Global] [Player] Steve →").body());
    }

    @Test
    void reportsThePrefixItStripped() {
        ChatLineSplitter.ChatLine line = ChatLineSplitter.split("<Alice> hola");
        assertEquals("<Alice> ", line.prefix());
    }

    @Test
    void handlesBlankAndNullInput() {
        assertEquals("", ChatLineSplitter.split(null).body());
        assertEquals("   ", ChatLineSplitter.split("   ").body());
    }
}
