/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ReportWindowTest
{
	@Test
	public void theReportCarriesTheWordsThePluginAndWhatTheGameShowed()
	{
		String body = ReportWindow.body("  it asks for the wrong thing  ", "Monster: Kraken (id 494)\n");
		assertTrue(body.contains("it asks for the wrong thing\n"));
		assertTrue(body.contains("**Plugin:** " + ReportWindow.PLUGIN + "\n"));
		assertTrue(body.endsWith("Monster: Kraken (id 494)\n"));
	}

	@Test
	public void withNothingWrittenItSaysSo()
	{
		assertTrue(ReportWindow.body("  ", null).contains("(nothing written)"));
	}

	@Test
	public void theLinkOpensANewIssueOnThePluginsRepositoryAndFitsInALink()
	{
		StringBuilder longBody = new StringBuilder();
		for (int i = 0; i < 900; i++)
		{
			longBody.append("a line of it\n");
		}
		String link = ReportWindow.link("wrong & odd", longBody.toString());
		assertTrue(link.startsWith("https://github.com/" + ReportWindow.REPOSITORY + "/issues/new?title=wrong%20%26%20odd&body="));
		assertTrue(link.contains("Cut%20short"));
		assertTrue(link.length() < 12000);
		assertEquals(-1, link.indexOf(' '));
	}
}
