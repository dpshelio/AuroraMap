/*
 * Written by Robert Fitzsimons <robfitz@273k.net>.
 * Released into the public domain 2012-04-22.
 *
 * Download and process POES data files with the aim of extracting Total
 * Energy Detector values for future processing and rendering of Aurora
 * activity.  POES data: http://satdat.ngdc.noaa.gov/sem/poes/data/
 *
 * A submission for the International Space Apps Challenge 2012
 * Challenge: Aurora Layer for Google Earth
 * http://spaceappschallenge.org/challenge/aurora-layer-google-earth/
 */
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.TimeZone;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public final class Aurora {
	private Aurora() {}

	private final static Logger LOGGER = Logger.getLogger(Aurora.class.getName());
	private final static TimeZone TIMEZONE = TimeZone.getTimeZone("UTC");
	private final static Locale LOCALE = Locale.UK;

	private final static String POES_AVG = "http://satdat.ngdc.noaa.gov/sem/poes/data/avg/txt/";
	private enum POESSatellite {
		METOP02("metop02/poes_m02_"),
		NOAA15("noaa15/poes_n15_"),
		NOAA16("noaa16/poes_n16_"),
		NOAA17("noaa17/poes_n17_"),
		NOAA18("noaa18/poes_n18_"),
		NOAA19("noaa19/poes_n19_"),
		;
		private POESSatellite(final String filePrefix) {
			this.filePrefix = filePrefix;
		}
		private final String filePrefix;
		public String getFilePrefix() {
			return this.filePrefix;
		}
	}

	private final static String buildFileName(final POESSatellite satellite, final Calendar calendar) {
		final StringBuilder sb = new StringBuilder();
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd", LOCALE); 
		sdf.setTimeZone(TIMEZONE);
		final int year = calendar.get(Calendar.YEAR);
		sb.append(year);
		sb.append("/");
		sb.append(satellite.getFilePrefix());
		sb.append(sdf.format(calendar.getTime()));
		sb.append(".txt");
		return sb.toString();
	}
	private final static InputStream getPOESFile(final POESSatellite satellite, final Calendar calendar) throws IOException {
		final String fileName = buildFileName(satellite, calendar);
		final File file = new File(fileName);
		InputStream inputStream = getCachedFile(file);
		if (inputStream == null) {
			if (downloadFile(POES_AVG + fileName, file)) {
				inputStream = getCachedFile(file);
			}
		}
		return inputStream;
	}
	private final static InputStream getCachedFile(final File file) throws IOException {
		if (file.exists()) {
			return new FileInputStream(file);
		}
		return null;
	}
	private final static boolean downloadFile(final String urlString, final File file) throws IOException {
		LOGGER.info(urlString);
		final URL url = new URL(urlString);
		final URLConnection urlConnection= url.openConnection();
		urlConnection.setConnectTimeout(15000);
		urlConnection.setReadTimeout(150000);
		HttpURLConnection httpURLConnection = null;
		InputStream inputStream = null;
		OutputStream outputStream = null;
		if (urlConnection instanceof HttpURLConnection) {
			httpURLConnection = (HttpURLConnection)urlConnection;
			httpURLConnection.setInstanceFollowRedirects(false);
		}
		try {
			urlConnection.connect();
			if (httpURLConnection != null) {
				final int responseCode = httpURLConnection.getResponseCode();
				if (responseCode != HttpURLConnection.HTTP_OK) {
					LOGGER.warning(responseCode + " '" + httpURLConnection.getResponseMessage() + "' " + urlString); 
					return false;
				}
			}
			inputStream = urlConnection.getInputStream();
			if (!file.getParentFile().exists()) {
				file.getParentFile().mkdirs();
			}
			outputStream = new FileOutputStream(file);
			final byte[] buffer = new byte[4096];
			for (int len; (len = inputStream.read(buffer)) > 0; ) {
				outputStream.write(buffer, 0, len);
			}
		} finally {
			if (inputStream != null) {
				inputStream.close();
			}
			if (outputStream != null) {
				outputStream.close();
			}
			if (httpURLConnection != null) {
				httpURLConnection.disconnect();
			}
		}
		return true;
	}

	public static void main(final String[] args) throws IOException, ParseException {
		final HashMap<POESSatellite, ArrayList<Sample[]>> satSamples = new HashMap<POESSatellite, ArrayList<Sample[]>>();

		for (final POESSatellite satellite : POESSatellite.values()) {
			final ArrayList<Sample> allSamples = new ArrayList<Sample>();

			final Calendar calendar = Calendar.getInstance(TIMEZONE, LOCALE);
			calendar.add(Calendar.DAY_OF_MONTH, 0);
			int tries = 4;
			int retrievedFiles = 0;
			do {
				final InputStream inputStream = getPOESFile(satellite, calendar);
				if (inputStream != null) {
					retrievedFiles++;
					allSamples.addAll(parse(satellite, new BufferedReader(new InputStreamReader(inputStream, "ASCII"))));
				}
				calendar.add(Calendar.DAY_OF_MONTH, -1);
			} while ((retrievedFiles < 2) && (tries-- > 0));

			if (allSamples.isEmpty()) {
				continue;
			}

			final ArrayList<Sample[]> passes = filter(allSamples);

			satSamples.put(satellite, passes);
		}

		Date date = null;
		for (final ArrayList<Sample[]> passes : satSamples.values()) {
			if (!passes.isEmpty()) {
				final Sample[] lastSamples = passes.get(passes.size() - 1);
				if (lastSamples.length > 0) {
					final Calendar calendar = Calendar.getInstance(TIMEZONE, LOCALE);
					calendar.setTime(lastSamples[0].date);
					calendar.set(Calendar.HOUR_OF_DAY, 0);
					calendar.set(Calendar.MINUTE, 0);
					calendar.set(Calendar.SECOND, 0);
					calendar.set(Calendar.MILLISECOND, 0);
					date = calendar.getTime();
				}
			}
			if (date != null) {
				break;
			}
		}

		final ArrayList<ArrayList<Sample[]>> satSamplesByHour = new ArrayList<ArrayList<Sample[]>>();
		for (int h = 0; h < 24; h++) {
			final ArrayList<Sample[]> byHour = new ArrayList<Sample[]>();
			satSamplesByHour.add(byHour);
		}

		for (final ArrayList<Sample[]> passes : satSamples.values()) {
			for (int p = passes.size() - 1; p >= 0; p--) {
				final Sample[] samples = passes.get(p);
				final int sampleLength = samples.length;
				if ((samples[0].date.compareTo(date) >= 0) || (samples[sampleLength - 1].date.compareTo(date) >= 0)) {
					for (int h = samples[0].hour; h <= samples[sampleLength - 1].hour; h++) {
						final ArrayList<Sample[]> byHour = satSamplesByHour.get(h);
						byHour.add(samples);
					}
				}
			}
		}


		for (int h = 0; h < satSamplesByHour.size(); h++) {
			final OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(h + ".txt"), "ASCII");
			final ArrayList<Sample[]> byHour = satSamplesByHour.get(h);
			writer.write(Sample.toHeaderString());
			writer.write("\n");
			for (final Sample[] samples : byHour) {
				final int sampleLength = samples.length;
				for (int i = 0; i < sampleLength; i++) {
					writer.write(samples[i].toString());
					writer.write("\n");
				}
			}
			writer.close();
		}
	}

	private final static class Sample implements Comparable<Sample> {
		private final static SimpleDateFormat sdf2 = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", LOCALE); 
		static {
			sdf2.setTimeZone(TIMEZONE);
		};
		public final Date date;
		public final int hour;
		public final double sslat;
		public final double sslong;
		public final double ted;
		public double avg_ted;
		public double fill_ted;
		private Sample(final Date date, final int hour, final double sslat, final double sslong, final double ted) {
			this.date = date;
			this.hour = hour;
			this.sslat = sslat;
			this.sslong = sslong;
			this.ted = ted;
		}
		public int compareTo(final Sample sample) {
			return this.date.compareTo(sample.date);
		}
		public void setAvgTed(final double avg_ted) {
			this.avg_ted = avg_ted;
			final double log_avg_ted = Math.log(this.avg_ted);
			this.fill_ted = Math.ceil(log_avg_ted);
		}
		public static String toHeaderString() {
			return "# sslat sslong fill_ted";
		}
		public String toString() {
			return this.sslat + " " + this.sslong + " " + this.fill_ted;
		}
	}

	private final static ArrayList<Sample> parse(final POESSatellite satellite, final BufferedReader reader) throws IOException, ParseException {
		final Pattern pattern = Pattern.compile(" +");
		final StringBuilder sb = new StringBuilder();
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyy MM dd HH mm ss", LOCALE); 
		sdf.setTimeZone(TIMEZONE);
		final ArrayList<Sample> samples = new ArrayList<Sample>();
		for (String line; ((line = reader.readLine()) != null); ) {
			final String[] parts = pattern.split(line, 0);
			if ((parts.length > 37) && (Character.isDigit(parts[0].charAt(0)))) {
				sb.setLength(0);
				sb.append(parts[0]).append(" ");
				sb.append(parts[1]).append(" ");
				sb.append(parts[2]).append(" ");
				sb.append(parts[3]).append(" ");
				sb.append(parts[4]).append(" ");
				sb.append(parts[5]);
				final Date date = sdf.parse(sb.toString());
				final int hour = Integer.parseInt(parts[3]);
				final double sslat = Double.parseDouble(parts[7]);
				final double sslong = Double.parseDouble(parts[8]);
				final double ted = Double.parseDouble(parts[37]);
				if ((ted > -990.0D) && (sslat > 30.0D)) {
					final Sample sample = new Sample(date, hour, sslat, sslong, ted);
					samples.add(sample);
				}
			}
		}
		return samples;
	}

	private final static ArrayList<Sample[]> filter(final ArrayList<Sample> allSamples) {
		Collections.sort(allSamples);

		final ArrayList<Sample[]> passes = new ArrayList<Sample[]>();
		final ArrayList<Sample> pass = new ArrayList<Sample>();

		for (final Sample sample : allSamples) {
			if (pass.size() > 0) {
				final Sample lastSample = pass.get(pass.size() - 1);
				final long diff_date = sample.date.getTime() - lastSample.date.getTime();
				if (diff_date > (10L * 60L * 1000L)) {
					passes.add(pass.toArray(new Sample[0]));
					pass.clear();
				}
			}
			pass.add(sample);
		}
		if (pass.size() > 0) {
			passes.add(pass.toArray(new Sample[0]));
			pass.clear();
		}

		for (final Sample[] samples : passes) {
			final int sampleLength = samples.length;
			for (int i = 0; i < sampleLength; i++) {
				double teds = 0.0D;
				int count = 0;
				for (int j = -10; j < 10; j++) {
					final int index = i + j;
					if ((index >= 0) && (index < sampleLength)) {
						teds += samples[i + j].ted;
						count++;
					}
				}
				samples[i].setAvgTed(teds / count);
			}
		}

		return passes;
	}
}

