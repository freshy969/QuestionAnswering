package qa.helper;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.HashMap;

import qa.Settings;
import qa.model.QueryTerm;
import qa.model.QueryTermImpl;
import qa.model.QuestionInfo;
import qa.model.QuestionInfoImpl;
import qa.model.enumerator.QueryType;
import qa.model.enumerator.QuerySubType;

//import edu.illinois.cs.cogcomp.lbj.chunk.ChunksAndPOSTags;

public class ClassifierHelper {

	private static ClassifierHelper instance;
	private final String re1 = "((?:[a-z][a-z]+))"; // Word 1
	private final String re2 = ":"; // Non-greedy match on filler
	private final String re3 = "((?:[a-z][a-z]+))"; // Word 2
	private final String re4 = " "; // Non-greedy match on filler
	private final String re5 = "((?:.*))"; // Variable Name 1

	private final Pattern p = Pattern.compile(re1 + re2 + re3 + re4 + re5,
			Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
	private HashMap<String, ArrayList<String>> semanticClasses;

	private ClassifierHelper() {

	}

	public static ClassifierHelper getInstance() {
		if (instance == null) {
			instance = new ClassifierHelper();
			instance.initSemanticClasses();
		}

		return instance;
	}

	private void initSemanticClasses() {
		semanticClasses = new HashMap<String, ArrayList<String>>();

		File folder = new File(Settings.get("SEMANTIC_CLASS_PATH"));
		if (!folder.exists()) {
			ApplicationHelper.printError("QuestionClassifier: Unable to load semantic class data");
		}

		for (File fileEntry : folder.listFiles()) {
			String semanticClass = fileEntry.getName();
			BufferedReader br = null;
			try {
				String line;
				br = new BufferedReader(new FileReader(fileEntry));
				while ((line = br.readLine()) != null) {
					String word = line.toLowerCase();
					if (!semanticClasses.containsKey(word)) {
						semanticClasses.put(word, new ArrayList<String>());
					}

					semanticClasses.get(word).add(semanticClass);
				}
			} catch (Exception e) {
				ApplicationHelper.printError("Question Classifier: Unable to load semantic class data", e);
			}
		}
	}

	public List<QuestionInfo> getAnnotatedData(String corpusPath,
			final String prefix, final String ext, final String chunkExt)
			throws Exception {
		if (corpusPath == null) {
			throw new Exception("Corpus path is missing");
		}
		List<QuestionInfo> trainingData = new ArrayList<QuestionInfo>();
		File folder = new File(corpusPath);
		File[] fileList = folder.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.toLowerCase().endsWith(ext.toLowerCase())
						&& name.toLowerCase().startsWith(prefix.toLowerCase());
			}
		});
		File[] chunkList = folder.listFiles(new FilenameFilter() {
			public boolean accept(File dir, String name) {
				return name.toLowerCase().endsWith(chunkExt.toLowerCase())
						&& name.toLowerCase().startsWith(prefix.toLowerCase());
			}
		});
		for (int i = 0; i < fileList.length; i++) {
			File file = fileList[i];
			File chunkFile = chunkList[i];
			int questionCount = 0;
			BufferedReader br = null;
			ArrayList<String> lines = new ArrayList<String>();
			ArrayList<String> chunkLines = new ArrayList<String>();
			try {
				String line;
				br = new BufferedReader(new FileReader(file));
				while ((line = br.readLine()) != null) {
					lines.add(line);
				}
				br.close();

				br = new BufferedReader(new FileReader(chunkFile));
				while ((line = br.readLine()) != null) {
					chunkLines.add(line);
				}
				br.close();

				for (int j = 0; j < lines.size(); j++) {
					questionCount++;
					trainingData.add(getQuestionInfo(lines.get(j),
							chunkLines.get(j)));
				}

				System.out.printf("Data set: %s [ %d questions]\n",
						file.getName(), questionCount);
			} catch (FileNotFoundException e) {
				ApplicationHelper.printError(String.format("Question Classifier: Corpus not found: %s/%s*%s",
						corpusPath, prefix, ext), e);
			} catch (IOException e) {
				ApplicationHelper.printError(String.format(
						"Question Classifier: Unable to read corpus: %s/%s*%s", corpusPath, prefix,
						ext), e);
			}

		}

		return trainingData;
	}

	private QuestionInfo getQuestionInfo(String text, String chunks) {
		Matcher m = p.matcher(text);
		if (m.find()) {
			String queryType = m.group(1);
			String querySubType = m.group(2);
			String rawQuestion = m.group(3);

			List<QueryTerm> terms = new ArrayList<QueryTerm>();
			terms.addAll(getPreloadedChunks(chunks));
			terms.addAll(getNameEntities(rawQuestion));
			terms.addAll(getQueryTerms(PosTagger.getInstance().tag(
					rawQuestion)));
			QuestionInfo questionInfo = new QuestionInfoImpl(
					QueryType.valueOf(queryType), QuerySubType.valueOf(String
							.format("%s_%s", queryType, querySubType)), terms,
					rawQuestion);
			// System.out.println(questionInfo);
			return questionInfo;
		} else {
			return null;
		}
	}

	public List<QueryTerm> getNameEntities(String raw) {
		List<String> entities = NeRecognizer.getInstance().getNameEntities(raw);
		List<QueryTerm> results = new ArrayList<QueryTerm>();
		for (String entity : entities) {
			results.add(new QueryTermImpl(entity));
		}

		return results;
	}

	public List<QueryTerm> getChunks(String question) {
		String chunks = ChunkerWrapper.getInstance().chunk(question);
		return getPreloadedChunks(chunks);
	}

	private List<QueryTerm> getPreloadedChunks(String chunks) {
		List<QueryTerm> terms = new ArrayList<QueryTerm>();
		Pattern chunkPattern = Pattern.compile("\\[(\\w+ )+\\]",
				Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
		Matcher m = chunkPattern.matcher(chunks);
		String firstNoun = null;
		String firstVerb = null;
		String questionWord = null;
		ArrayList<String> questionWords = new ArrayList<String>(
				Arrays.asList(new String[] { "[ADVP How", "[NP What",
						"[NP Who", "[NP Which", "[ADVP Where", "[ADVP When" }));
		while (m.find()) {
			String chunk = m.group();
			if (firstNoun == null || firstVerb == null || questionWord == null) {
				boolean isQWord = false;
				for (String qWord : questionWords) {
					if (chunk.toLowerCase().startsWith(qWord.toLowerCase())) {
						isQWord = true;
						break;
					}
				}

				if (!isQWord) {
					if (firstNoun == null && chunk.startsWith("[NP")) {
						firstNoun = chunk;
					}

					if (firstVerb == null && chunk.startsWith("[VP")) {
						firstVerb = chunk;
					}
				} else {
					terms.add(new QueryTermImpl(chunk));
				}
			} else {
				break;
			}

			// terms.add(new QueryTermImpl(chunk));
		}

		if (firstNoun != null) {
			terms.add(new QueryTermImpl(firstNoun));
		}

		if (firstVerb != null) {
			terms.add(new QueryTermImpl(firstVerb));
		}

		return terms;
	}

	public List<QueryTerm> getQueryTerms(String text) {
		List<QueryTerm> terms = new ArrayList<QueryTerm>();
		Pattern wordPattern = Pattern.compile("\\w+_\\w+",
				Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
		Matcher m = wordPattern.matcher(text);
		while (m.find()) {
			String tagged = m.group();

			Pattern taggedPattern = Pattern.compile("((?:\\w+))_((?:\\w+))",
					Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
			Matcher taggedMatcher = taggedPattern.matcher(tagged);
			if (taggedMatcher.find()) {
				String word = taggedMatcher.group(1);
				String pos = taggedMatcher.group(2);
				// if (pos.equals("CD") || pos.equals("NNP") ||
				// pos.equals("NNPS")
				// || pos.equals("FW")) {
				// // System.out.print(pos);
				terms.add(new QueryTermImpl(pos + "_" + word));
				// } else {
				// System.out.print(word);
				// if (!(pos.equals("CD") || pos.equals("NNP") ||
				// pos.equals("NNPS"))) {
				terms.add(new QueryTermImpl(word));
				// }

				ArrayList<String> semClass = getSemanticClass(word
						.toLowerCase());
				if (semClass != null) {
					for (String s : semClass) {
						terms.add(new QueryTermImpl(s));
					}
				}
			}

			// System.out.print(" ");
		}
		// System.out.println();

		return terms;
	}

	public List<QueryTerm> getSearchEngineQueryTerms(String text) {
		text = processAbbr(text);
		List<QueryTerm> terms = new ArrayList<QueryTerm>();
		Pattern wordPattern = Pattern.compile("\\w+", Pattern.CASE_INSENSITIVE
				| Pattern.DOTALL);
		Matcher m = wordPattern.matcher(text);
		while (m.find()) {
			String word = m.group();
			if (!isQuestionWord(word)) {
				terms.add(new QueryTermImpl(word));
			}
		}

		return terms;
	}

	private String processAbbr(String text) {
		return text.replace(".", "");
	}

	private static final List<String> questionWords = new ArrayList<String>(
			Arrays.asList(new String[] { "who", "whom", "what", "when",
					"where", "how", "which" }));

	private boolean isQuestionWord(String word) {
		word = word.toLowerCase();
		return questionWords.contains(word);
	}

	private ArrayList<String> getSemanticClass(String word) {
		if (semanticClasses.containsKey(word)) {
			return semanticClasses.get(word);
		} else {
			return null;
		}
	}

	public List<QueryType> getAllQueryTypes() {
		return Arrays.asList(QueryType.class.getEnumConstants());
	}

	public List<QuerySubType> getAllQuerySubTypes() {
		return Arrays.asList(QuerySubType.class.getEnumConstants());
	}

	public List<String> getStopWords(String path) {
		List<String> stopWords = new ArrayList<String>();

		Pattern wordPattern = Pattern.compile("\\w+", Pattern.CASE_INSENSITIVE
				| Pattern.DOTALL);
		String line;
		try {
			File file = new File(path);
			BufferedReader br = new BufferedReader(new FileReader(file));
			while ((line = br.readLine()) != null) {
				Matcher m = wordPattern.matcher(line);
				while (m.find()) {
					stopWords.add(m.group());
				}
			}
			br.close();
		} catch (FileNotFoundException e) {
			ApplicationHelper.printWarning("Question Classifier: Unable to find stopword list");
		} catch (IOException e) {
			ApplicationHelper.printWarning("Question Classifier: Unable to read stopword list");
		}

		return stopWords;
	}
}
