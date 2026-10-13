/*
 * Copyright (c) 2026, GuimGod
 * All rights reserved. Licensed under the BSD 2-Clause License (see LICENSE).
 */
package com.boltswapreminder;

import java.awt.Color;
import java.awt.Component;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.util.LinkBrowser;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * The window of "Bug or suggestion": the player's words, and under them what goes with them (the
 * monster, the items worn and carried, by id), exactly as it is sent. Nothing goes until the
 * player presses a button, and it carries no name of the player or of the account.
 */
final class ReportWindow
{
	static final String PLUGIN = "Bolt Swap Reminder";
	/** Where issues are posted. */
	static final String REPOSITORY = "GuimGod/bolt-swap-reminder";
	/** The author's form: the address that takes answers, and the names of its fields. */
	private static final String FORM = "https://docs.google.com/forms/d/e/1FAIpQLSclvrdcVzomW3-4lj7-gnvbzmerY6qwHepApCuI7e4Q8raDyw/formResponse";
	private static final String FIELD_PLUGIN = "entry.2100394972";
	private static final String FIELD_MONSTER = "entry.676895816";
	private static final String FIELD_WORDS = "entry.1125054442";
	private static final String FIELD_REPORT = "entry.1644804003";
	/** A link longer than this is cut short by browsers and by the site: the whole report is copied instead. */
	private static final int LONGEST_BODY_IN_A_LINK = 5500;
	private static final Color GOOD = new Color(0, 200, 83);
	private static final Color BAD = new Color(255, 82, 82);

	private ReportWindow()
	{
	}

	/** What the game shows now, for whoever reads the report. Call it on the client thread. */
	static String context(Client client, NPC monster)
	{
		StringBuilder out = new StringBuilder();
		out.append("Monster: ").append(monster == null || monster.getName() == null ? "none"
			: monster.getName() + " (id " + monster.getId() + ")").append('\n');
		out.append("Worn (item ids): ").append(ids(client.getItemContainer(InventoryID.WORN))).append('\n');
		out.append("Inventory (item ids): ").append(ids(client.getItemContainer(InventoryID.INV))).append('\n');
		Player player = client.getLocalPlayer();
		if (player != null && player.getWorldLocation() != null)
		{
			out.append("Region: ").append(player.getWorldLocation().getRegionID()).append('\n');
		}
		return out.toString();
	}

	private static String ids(ItemContainer container)
	{
		List<String> ids = new ArrayList<>();
		if (container != null)
		{
			for (Item item : container.getItems())
			{
				if (item != null && item.getId() > 0)
				{
					ids.add(item.getQuantity() > 1 ? item.getId() + "x" + item.getQuantity() : String.valueOf(item.getId()));
				}
			}
		}
		return ids.isEmpty() ? "none" : String.join(", ", ids);
	}

	/** The report as it is sent: the player's words, then what the game showed. */
	static String body(String words, String context)
	{
		return "**What is wrong, or what would be better**\n"
			+ (words == null || words.trim().isEmpty() ? "(nothing written)" : words.trim())
			+ "\n\n**Plugin:** " + PLUGIN + "\n" + (context == null ? "" : context);
	}

	/** The page where the report is posted as an issue, already filled in. */
	static String link(String words, String body)
	{
		String first = words == null ? "" : words.trim().replaceAll("\\s+", " ");
		if (first.length() > 60)
		{
			first = first.substring(0, 60).trim() + "...";
		}
		String text = body;
		if (text.length() > LONGEST_BODY_IN_A_LINK)
		{
			int cut = text.lastIndexOf('\n', LONGEST_BODY_IN_A_LINK);
			text = text.substring(0, cut < 0 ? LONGEST_BODY_IN_A_LINK : cut)
				+ "\n\n(Cut short to fit in a link. Paste the copied report here for the rest.)";
		}
		return "https://github.com/" + REPOSITORY + "/issues/new?title=" + encode(first.isEmpty() ? "Bug or suggestion" : first)
			+ "&body=" + encode(text);
	}

	private static String encode(String text)
	{
		try
		{
			return URLEncoder.encode(text, "UTF-8").replace("+", "%20");
		}
		catch (UnsupportedEncodingException e)
		{
			throw new IllegalStateException(e);
		}
	}

	/** Opens the window. Call it on the Swing thread. */
	static void open(Component over, String monster, String context, OkHttpClient http)
	{
		Window owner = over == null ? null : SwingUtilities.getWindowAncestor(over);
		final JDialog dialog = new JDialog(owner, PLUGIN + ": bug or suggestion");
		final JTextArea words = new JTextArea(5, 46);
		words.setLineWrap(true);
		words.setWrapStyleWord(true);
		final JTextArea sent = new JTextArea(10, 46);
		sent.setEditable(false);
		sent.setLineWrap(true);
		sent.setWrapStyleWord(true);
		sent.setText(body("", context));
		sent.setCaretPosition(0);
		words.getDocument().addDocumentListener(new DocumentListener()
		{
			private void again()
			{
				sent.setText(body(words.getText(), context));
				sent.setCaretPosition(0);
			}

			@Override
			public void insertUpdate(DocumentEvent e)
			{
				again();
			}

			@Override
			public void removeUpdate(DocumentEvent e)
			{
				again();
			}

			@Override
			public void changedUpdate(DocumentEvent e)
			{
				again();
			}
		});
		final JLabel said = new JLabel(" ");
		final JButton send = new JButton("Send the report");
		send.addActionListener(e ->
		{
			send.setEnabled(false);
			said.setForeground(null);
			said.setText("Sending...");
			FormBody form = new FormBody.Builder()
				.add(FIELD_PLUGIN, PLUGIN)
				.add(FIELD_MONSTER, monster == null ? "" : monster)
				.add(FIELD_WORDS, words.getText())
				.add(FIELD_REPORT, sent.getText())
				.build();
			http.newCall(new Request.Builder().url(FORM).post(form).build()).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException failure)
				{
					SwingUtilities.invokeLater(() -> done(false));
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					final boolean went = response.isSuccessful();
					response.close();
					SwingUtilities.invokeLater(() -> done(went));
				}

				private void done(boolean went)
				{
					said.setForeground(went ? GOOD : BAD);
					said.setText(went ? "Sent. Thank you." : "It did not go. Try again, or use one of the other two.");
					send.setEnabled(!went);
				}
			});
		});
		JButton post = new JButton("Open the report on GitHub");
		post.addActionListener(e ->
		{
			LinkBrowser.browse(link(words.getText(), sent.getText()));
			said.setForeground(null);
			said.setText("Opened in your browser: post it there. It needs a GitHub account.");
		});
		JButton copy = new JButton("Copy the report");
		copy.addActionListener(e ->
		{
			try
			{
				Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(sent.getText()), null);
				said.setForeground(GOOD);
				said.setText("Copied.");
			}
			catch (IllegalStateException busy)
			{
				said.setForeground(BAD);
				said.setText("The clipboard is busy. Try again.");
			}
		});
		JButton close = new JButton("Close");
		close.addActionListener(e -> dialog.dispose());
		JPanel buttons = new JPanel();
		buttons.add(send);
		buttons.add(post);
		buttons.add(copy);
		buttons.add(close);
		JPanel all = new JPanel();
		all.setLayout(new BoxLayout(all, BoxLayout.Y_AXIS));
		all.setBorder(new EmptyBorder(10, 10, 10, 10));
		all.add(left(new JLabel("What is wrong, or what would you like? Say what you expected instead.")));
		all.add(Box.createVerticalStrut(4));
		all.add(left(new JScrollPane(words)));
		all.add(Box.createVerticalStrut(8));
		all.add(left(new JLabel("This is what is sent with it. It has no name of yours or of your account:")));
		all.add(Box.createVerticalStrut(4));
		all.add(left(new JScrollPane(sent)));
		all.add(Box.createVerticalStrut(6));
		all.add(left(new JLabel("\"Send\" sends what is above, and nothing else, to the plugin's author. Nothing goes until you press it.")));
		all.add(left(said));
		all.add(left(buttons));
		dialog.setContentPane(all);
		dialog.pack();
		dialog.setLocationRelativeTo(owner);
		dialog.setVisible(true);
	}

	private static <T extends JComponent> T left(T component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		return component;
	}
}
