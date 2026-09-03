package com.sparxilium.smartpluginassistant.model.spiget;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class SpigetResource {
    private long id;
    private String name;
    private String tag;
    private VersionRef version;
    private AuthorRef author;
    private FileInfo file;
    private Rating rating;
    private Icon icon;
    private int downloads;
    private boolean external;
    private boolean premium;
    private Double price;
    private String currency;
    private List<String> testedVersions;
    private Long releaseDate;
    private Long updateDate;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class VersionRef {
        private long id;
        public long getId() { return id; }
        public void setId(long id) { this.id = id; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthorRef {
        private long id;
        private String name;

        public long getId() { return id; }
        public void setId(long id) { this.id = id; }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileInfo {
        private String type;
        private double size;
        private String sizeUnit;
        private String url;
        private String externalUrl;

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public double getSize() { return size; }
        public void setSize(double size) { this.size = size; }

        public String getSizeUnit() { return sizeUnit; }
        public void setSizeUnit(String sizeUnit) { this.sizeUnit = sizeUnit; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getExternalUrl() { return externalUrl; }
        public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Rating {
        private int count;
        private double average;

        public int getCount() { return count; }
        public void setCount(int count) { this.count = count; }

        public double getAverage() { return average; }
        public void setAverage(double average) { this.average = average; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Icon {
        private String url;
        private String data;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getData() { return data; }
        public void setData(String data) { this.data = data; }
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTag() { return tag; }
    public void setTag(String tag) { this.tag = tag; }

    public VersionRef getVersion() { return version; }
    public void setVersion(VersionRef version) { this.version = version; }

    public AuthorRef getAuthor() { return author; }
    public void setAuthor(AuthorRef author) { this.author = author; }

    public FileInfo getFile() { return file; }
    public void setFile(FileInfo file) { this.file = file; }

    public Rating getRating() { return rating; }
    public void setRating(Rating rating) { this.rating = rating; }

    public Icon getIcon() { return icon; }
    public void setIcon(Icon icon) { this.icon = icon; }

    public int getDownloads() { return downloads; }
    public void setDownloads(int downloads) { this.downloads = downloads; }

    public boolean isExternal() { return external; }
    public void setExternal(boolean external) { this.external = external; }

    public boolean isPremium() { return premium; }
    public void setPremium(boolean premium) { this.premium = premium; }

    public Double getPrice() { return price; }
    public void setPrice(Double price) { this.price = price; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public List<String> getTestedVersions() { return testedVersions; }
    public void setTestedVersions(List<String> testedVersions) { this.testedVersions = testedVersions; }

    public Long getReleaseDate() { return releaseDate; }
    public void setReleaseDate(Long releaseDate) { this.releaseDate = releaseDate; }

    public Long getUpdateDate() { return updateDate; }
    public void setUpdateDate(Long updateDate) { this.updateDate = updateDate; }

    public String getSpigotUrl() {
        return "https://www.spigotmc.org/resources/" + id + "/";
    }

    public String getIconUrl() {
        if (icon != null && icon.getUrl() != null && !icon.getUrl().isBlank()) {
            String u = icon.getUrl().trim();
            if (u.startsWith("//")) return "https:" + u;
            if (u.startsWith("http://") || u.startsWith("https://")) return u;
            if (u.startsWith("/")) return "https://www.spigotmc.org" + u;
            return "https://www.spigotmc.org/" + u;
        }
        return null;
    }
}
