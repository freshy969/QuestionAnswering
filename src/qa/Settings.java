package qa;

import java.util.Properties;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

import qa.helper.ApplicationHelper;

public class Settings {
	private static Properties instance;

	private static void load() {
		File file = new File("Application.properties");
		instance = new Properties();
		try {
			instance.load(new FileInputStream(file));
		} catch (IOException e) {
			ApplicationHelper.printError(String.format(
					"Application: Unable to load application settings from %s",
					file.getAbsolutePath()), e);
		}
	}

	public static String get(String key) {
		if (instance == null) {
			load();
		}

		return instance.getProperty(key);
	}
}