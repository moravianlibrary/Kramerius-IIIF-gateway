require 'net/https'
require 'open-uri'
require 'nokogiri'
require 'json'
require 'typhoeus'
# require 'fastimage'

# @uuid = ARGV[0]
if ARGV.length == 1
    @link = ARGV[0]
    @library = @link.split("/")[3]
    object = @link.split("/")[5]
    @uuid = object.split("?")[0]
else 
    @library = ARGV[0]
    @uuid = ARGV[1]
    full = ARGV[2]
    if (full == 'full')
        @full = true
    else
        @full = false
    end
end
@registrkrameriu = "https://registr.digitalniknihovna.cz/api/libraries"
@url_manifest = "https://iiif.digitalniknihovna.cz"
@mods
@canvasIndex = 0

@languages = {"cze" => "čeština", "ger" => "němčina", "eng" => "angličtina", "lat" => "latina", "fre" => "francouzština",
              "rus" => "ruština", "pol" => "polština", "slv" => "slovinština", "slo" => "slovenština", 
              "ita" => "italština", "dut" => "nizozemština", "hun" => "maďarština", "ukr" => "ukrajinština",
              "rum" => "rumunština", "sla" => "slovanské jazyky", "grc" => "starořečtina", "spa" => "španělština", "scr" => "chorvatština", 
              "und" => "neurčený jazyk", "zxx" => "žádný lingvistický obsah", "---" => "žádný lingvistický obsah", }

@mapping = {
    "k5" => {
        "solr" => "/search/api/v5.0/search?",
        "items" => "/search/api/v5.0/item/",
        "mods_url" => "/streams/BIBLIO_MODS",
        "thumb" => "/thumb",
        "pid" => "PID",
        "fedora_model" => "fedora.model",
        "root_title_url" => "root_title",
        "details" => "details",
        "title" => "title",
        "parent_pid" => "parent_pid",
        "rels_ext_index" => "rels_ext_index",
        "alto_url" => "/streams/ALTO",
        "root_pid" => "root_pid",
        "mp3_url" => "/streams/MP3",
        "pdf" => "img_full_mime"
    },
    "k7" => {
        "solr" => "/search/api/client/v7.0/search?",
        "items" => "/search/api/client/v7.0/items/",
        "mods_url" => "/metadata/mods",
        "thumb" => "/image/thumb",
        "pid" => "pid",
        "fedora_model" => "model",
        "root_title_url" => "root.title",
        "title" => "title.search",
        "parent_pid" => "own_parent.pid",
        "rels_ext_index" => "rels_ext_index.sort",
        "details" => "date.str,part.number.str,part.name,issue.type.code,page.number,page.type",
        "alto_url" => "/ocr/alto",
        "root_pid" => "root.pid",
        "mp3_url" => "/audio/mp3",
        "pdf" => "ds.img_full.mime"
    }
}

def get_xml(url)
    uri = URI(url) 
    # uri = URI(URI.encode(url))
    https = Net::HTTP.new(uri.host, uri.port)
    https.use_ssl = true
    https.verify_mode = OpenSSL::SSL::VERIFY_PEER
    https.open_timeout = 100
    https.read_timeout = 100
    request = Net::HTTP::Get.new(uri)
    request["Accept"] = 'application/xml'
    response = https.request(request).read_body
end

def get_json(url)
    uri = URI(url) #uri = URI(URI.encode(url))
    https = Net::HTTP.new(uri.host, uri.port)
    https.use_ssl = true
    request = Net::HTTP::Get.new(uri)
    request.add_field "Content-Type", "application/json; charset=utf-8"
    request.add_field "Accept", "application/json"
    response = https.request(request)
    result =  JSON.parse(response.read_body)
    return result
end

def control_404(url)
    response = Net::HTTP.get_response(URI(url))
    error = response.is_a?(Net::HTTPNotFound)
    code = response.code
    if code.to_i > 400
        result = true
    end
    return !result
end

def create_provider(library)
    object = get_json(@registrkrameriu)
    # K7 TEST
    if library == "mzkk7"
        id = "#{@registrkrameriu}/#{@library}"
        label_cz = object.find {|h1| h1['code'] == "mzk"}["name"]
        label_en = object.find {|h1| h1['code'] == "mzk"}["name_en"]
        homepage = "https://k7-test.mzk.cz"
        version = "7"
        logo = object.find {|h1| h1['code'] == "mzk"}["logo"]
        provider = [{"id" => id, "type" => "Agent", "label" => {"cz" => [label_cz]}, "homepage" => [{ "id" => homepage, "type" => "Text", "label" => {"cz" => [label_cz]}, "format" => "text/html"}], "logo" => [{"id" => logo, "type" => "Image", "format" => "image/png"}]}]
    else
        id = "#{@registrkrameriu}/#{@library}"
        label_cz = object.find {|h1| h1['code'] == library}["name"]
        label_en = object.find {|h1| h1['code'] == library}["name_en"]
        homepage = object.find {|h1| h1['code'] == library}["url"]
        version = object.find {|h1| h1['code'] == library}["version"]
        logo = object.find {|h1| h1['code'] == library}["logo"]
        provider = [{"id" => id, "type" => "Agent", "label" => {"cz" => [label_cz]}, "homepage" => [{ "id" => homepage, "type" => "Text", "label" => {"cz" => [label_cz]}, "format" => "text/html"}], "logo" => [{"id" => logo, "type" => "Image", "format" => "image/png"}]}]
    end
    return [provider, homepage, version]
end

def find_document_properties(pid)
    document = {}
    uuid = "#{@uuid}".to_json
    @solr_url = @mapping["#{@v}"]["solr"]
    @pid = @mapping["#{@v}"]["pid"]
    @parent_pid = @mapping["#{@v}"]["parent_pid"]
    @fedora_model = @mapping["#{@v}"]["fedora_model"]
    @root_title_url = @mapping["#{@v}"]["root_title_url"]
    @details = @mapping["#{@v}"]["details"]
    @title_url = @mapping["#{@v}"]["title"]
    @rels_ext_index = @mapping["#{@v}"]["rels_ext_index"]
    @items = @mapping["#{@v}"]["items"]
    @alto_url = @mapping["#{@v}"]["alto_url"]
    @mods_url = @mapping["#{@v}"]["mods_url"]
    @thumb_url = @mapping["#{@v}"]["thumb"]
    @mp3_url = @mapping["#{@v}"]["mp3_url"]
    @pdf = @mapping["#{@v}"]["pdf"]

    solr_request = "#{@kramerius}#{@solr_url}fl=#{@fedora_model},#{@root_title_url},#{@details},#{@title_url},#{@pdf}&q=#{@pid}:#{uuid}&rows=1500&start=0"
    object = get_json(solr_request)
    response_body = object["response"]["docs"][0]
    if !response_body.nil?
        document["model"] = response_body["#{@fedora_model}"]
        document["root_title"] = response_body["#{@root_title_url}"].split(":")[0]
        document["title"] = response_body["#{@title_url}"]
        document["pdf"] = response_body["#{@pdf}"]
        if @v == "k5"
            if document["model"].to_s == "periodicalvolume"
                if !response_body["details"][0].split("##")[0].nil?
                    document["date"] = response_body["details"][0].split("##")[0].strip.sub(" ", "")
                end
                if !response_body["details"][0].split("##")[1].nil?
                    document["number"] = response_body["details"][0].split("##")[1].strip.sub(" ", "")
                end  
            elsif document["model"].to_s == "periodicalitem"
                if !response_body["details"][0].split("##")[2].nil?
                    document["date"] = response_body["details"][0].split("##")[2].strip.sub(" ", "")
                end
                if !response_body["details"][0].split("##")[3].nil?
                    document["number"] = response_body["details"][0].split("##")[3].strip.sub(" ", "")
                end  
            elsif document["model"].to_s == "monograph"
                object = get_json("#{@kramerius}/search/api/v5.0/search?fl=PID,details,rels_ext_index,fedora.model&q=parent_pid:#{uuid} AND fedora.model:monographunit&rows=1500&start=0")
                response_body = object["response"]["docs"]
                if !response_body[0].nil?
                    document["model"] = 'monographcollection'
                end
            end
            if document["pdf"] == "application/pdf"
                document["model"] = 'pdf'
            end
        elsif @v == "k7"
            if document["model"].to_s == "periodicalvolume" || document["model"].to_s == "periodicalitem"
                if !response_body["date.str"].nil?
                    document["date"] = response_body["date.str"]
                end
                if !response_body["part.number.str"].nil?
                    document["number"] = response_body["part.number.str"]
                end
                if !response_body["issue.type.code"].nil?
                    document["issue_type"] = response_body["issue.type.code"]
                end
            elsif document["model"].to_s == "monograph"
                object = get_json("#{@kramerius}#{@solr_url}fl=#{@fedora_model}&q=own_parent.pid:#{uuid} AND #{@fedora_model}:monographunit&rows=1500&start=0")
                response_body = object["response"]["docs"]
                if !response_body[0].nil?
                    document["model"] = 'monographcollection'
                end
            end
            if document["pdf"] == "application/pdf"
                document["model"] = 'pdf'
            end       
        end
    end
    return document
end

def mods_extractor
    errors = []
    mods = {}
    @mods_url = "#{@kramerius}#{@items}#{@uuid}#{@mods_url}"
    xmldoc = Nokogiri::XML get_xml(@mods_url)
        if xmldoc.nil? 
            errors << uuid
            continue
        end

    xmldoc.remove_namespaces!

    # sysno a sigla
    xmldoc.xpath("modsCollection/mods/recordInfo/recordIdentifier").each do |sys|
        sysno = "#{sys.text}"
        sigla = "MZK01"  #TODO
        if sysno.length == 9
            mods["sysno"] = sysno    
        end
        mods["sigla"] = sigla
    end

    # uuid
    xmldoc.xpath("modsCollection/mods/identifier").each do |id|
        if id.at("@type").to_s == "uuid"
            uuid = id.text
            if uuid.length == 36
                uuid = "uuid:#{id.text}"
            else
                uuid = "#{id.text}"
            end
            uuid2 = uuid.to_s
            mods["uuid"] = uuid2
        end

    end

    # title
    xmldoc.xpath("modsCollection/mods/titleInfo[1]").each do |titleInfo|
        if !titleInfo.at("title").nil?
            title = titleInfo.at("title").text
            if !titleInfo.at("nonSort").nil?
                nonsort = titleInfo.at("nonSort").text
                mods["title"] = "#{nonsort}#{title}"
            else
                mods["title"] = title
            end
        end
        if !titleInfo.at("subTitle").nil?
            subtitle = titleInfo.at("subTitle").text
            mods["subtitle"] = subtitle
        end
        if !titleInfo.at("partNumber").nil?
            partNumber = titleInfo.at("partNumber").text
            mods["partNumber"] = partNumber
        end
        if !titleInfo.at("partName").nil?
            partName = titleInfo.at("partName").text
            mods["partName"] = partName
        end
    end

    # authors
    if xmldoc.at("modsCollection/mods/name")
        authors = []
        xmldoc.xpath("modsCollection/mods/name").each do |name|
            author = ""
            if name.at("//namePart/@type").to_s == "family"
                family = ""
                given = ""
                termOfAddress = ""
                date = ""
                name.xpath("namePart").each do |namePart|
                    if namePart.at("@type").to_s == "family"
                        family = namePart.text
                    end
                    if namePart.at("@type").to_s == "given"
                        given = ", #{namePart.text}"
                    end
                    if namePart.at("@type").to_s == "termsOfAddress"
                        termOfAddress = ", #{namePart.text}"
                    end
                    if namePart.at("@type").to_s == "date"
                        date = ", #{namePart.text}"
                    end
                end
                author = "#{family}#{given}#{termOfAddress}#{date}"
            end          
            if name.at("//namePart[not(@type)]")
                name2 = ""
                termOfAddress = ""
                date = ""
                name.xpath("namePart").each do |namePart|
                    if namePart.at("@type").to_s == "termsOfAddress"
                        termOfAddress = ", #{namePart.text}"
                    elsif namePart.at("@type").to_s == "date"
                        date = ", #{namePart.text}"
                    # elsif namePart.at("[not(@type)]")
                    else
                        name2 = namePart.text
                    end   
                end
                author = "#{name2}#{termOfAddress}#{date}"
            end

            authors.push(author)
            mods["authors"] = authors
        end
    end

    # nakladatelske udaje
    xmldoc.xpath("modsCollection/mods/originInfo").each do |originInfo|
    published = ""    
        # place
        originInfo.xpath("place").each do |place|
            if place.xpath("placeTerm/@authority").to_s != "marccountry"
                places = place.at("placeTerm").text
                published = "#{published}#{places}"
            # else
            #     places = place.at("placeTerm").text
            #     published = "#{published}#{places}"
            end
        end
        
        # publisher
        if !originInfo.xpath("publisher").nil?
            originInfo.xpath("publisher").each do |pub|
                publisher = pub.text
                published = "#{published}: #{publisher}"
            end
        end

        # date
        if !originInfo.xpath("dateIssued").nil?
            dates = {}
            originInfo.xpath("dateIssued").each do |dat|
                if dat.at("@point").to_s == "start"
                    dates["date_start"] = dat.text
                elsif dat.at("@point").to_s == "end"
                    dates["date_end"] = dat.text
                else
                    dates["date"] = dat.text
                    mods["date"] = dat.text
                end
            end
            if published.length > 0
                published = "#{published}, #{dates["date"]}"
            else
                published = "#{dates["date"]}"
            end
        end
        mods["published"] = published
    end
    # jazyk
    langs = []
    xmldoc.xpath("modsCollection/mods/language/languageTerm").each do |lang|
        if !@languages[lang.text].nil?
            langs.push(@languages[lang.text])
        else 
            langs.push(lang.text)
        end
        mods["languages"] = langs
    end
    # coordinates
    xmldoc.xpath("modsCollection/mods/subject/cartographics/coordinates").each do |c|
        mods["coordinates"] = c.text
    end
    return mods
end

def create_label(uuid)
    label = {}
    if @document_model == "periodicalvolume" || @document_model == "periodicalitem"
        partDate = @mods["date"]
        label = {"none" => ["#{@root_title} (#{@document["date"]})"]}
    elsif @document_model == "soundunit"
            label = {"none" => ["#{@root_title} (#{@mods["partName"]})"]}
    else label = {"cz" => [@mods["title"]]}
    end
    return label
end

def create_metadata
    metadata = []
    if !@mods["title"].nil?
        title = {"label" => {"cz" => ["Název"]}, "value" => {"none" => [@mods["title"]]}}
        metadata.push(title)
    end
    if !@mods["subtitle"].nil?
        subtitle = {"label" => {"cz" => ["Podnázev"]}, "value" => {"none" => [@mods["subtitle"]]}}
        metadata.push(subtitle)
    end
    if !@type_of_resource.nil?
        type_of_resource = {"label" => {"cz" => ["Typ dokumentu"]}, "value" => {"cz" => [@type_of_resource]}}
        metadata.push(type_of_resource)
    end
    # if !@mods["partNumber"].nil?
    #     title = {"label" => {"cz" => ["Číslo části"]}, "value" => {"none" => [@mods["partNumber"]]}}
    #     metadata.push(title)
    # end
    if !@mods["authors"].nil?
        author = {"label" => {"cz" => ["Autor"]}, "value" => {"none" => [@mods["authors"]]}}
        metadata.push(author)
    end
    if @document_model == "periodicalvolume" || @document_model == "periodicalitem"
        number = {"label" => {"cz" => ["Číslo části"]}, "value" => {"none" => [@document["number"]]}}
        date = {"label" => {"cz" => ["Vydáno"]}, "value" => {"none" => [@document["date"]]}}
        metadata.push(number)
        metadata.push(date)
    elsif @document_model == "soundunit" || @document_model == "monographunit"
        number = {"label" => {"cz" => ["Číslo části"]}, "value" => {"none" => [@mods["partNumber"]]}}
        name = {"label" => {"cz" => ["Název části"]}, "value" => {"none" => [@mods["partName"]]}}
        metadata.push(number)
        metadata.push(name)
    elsif !@mods["published"].nil?
        if @mods["published"].length > 0
            published = {"label" => {"cz" => ["Nakladatelské údaje"]}, "value" => {"none" => [@mods["published"]]}}
            metadata.push(published)
        end
    end
    if !@mods["languages"].nil?
        subtitle = {"label" => {"cz" => ["Jazyk"]}, "value" => {"none" => [@mods["languages"]]}}
        metadata.push(subtitle)
    end
    # return JSON.pretty_generate(metadata)
    return metadata
end

def create_homepage
    uuid = @uuid
    sysno = @mods["sysno"]
    sigla = @mods["sigla"]
    homepage = []
    if !uuid.nil?
        dk = {"id" => "https://www.digitalniknihovna.cz/#{@library}/uuid/#{uuid}", "type" => "Text", "label" => { "cz" => ["Odkaz do Digitální knihovny"]}}
        homepage.push(dk)
    end
    if @library == "mzk"
        if !sysno.nil?
            vufind = {"id" => "https://vufind.mzk.cz/Record/#{sigla}-#{sysno}", "type" => "Text", "label" => { "cz" => ["Odkaz do Vufindu"]}}
            homepage.push(vufind)
        end
    end
    return homepage
end
def create_homepage_periodical_volume_issue
    uuid = @uuid
    homepage = []
    if !uuid.nil?
        dk = {"id" => "https://www.digitalniknihovna.cz/mzk/uuid/#{uuid}", "type" => "Text", "label" => { "cz" => ["Odkaz do Digitální knihovny"]}}
        homepage.push(dk)
    end
    return homepage
end

def create_behavior
    behavior = ["paged"]
    return behavior
end

def create_thumbnail(uuid)
    # K5 https://kramerius.mzk.cz/search/api/v5.0/item/uuid:bdc28360-3fc8-11e7-b3c8-005056825209/thumb
    # K7 https://k7-test.mzk.cz/search/api/client/v7.0/items/uuid:4a6d8f19-13ab-4804-a26f-d877f1940970/image/thumb
    thumbnail = {"id" => "#{@kramerius}" + @mapping["#{@v}"]["items"] + "#{@uuid}" + @mapping["#{@v}"]["thumb"],
                 "type" => "Image",
                 "format" => "image/jpeg",
                #  "service" => [{"@id" => "#{@kramerius}/search/iiif/#{@uuid}",
                #                "@type" => "ImageService2",
                #                "profile" => "http://iiif.io/api/image/2/level2.json"
                #               }]
                }
    return [thumbnail]
end

def create_navPlace
    coordinates = parse_coordinates
    type = coordinates[0]
    features = {
        "id" => "#{@url_manifest}/#{@library}/#{@uuid}/feature/1",
        "type" => "Feature",
        "properties" => {
            "label" => {"en" => [@title]}
        },
        "geometry" => {
            "type" => type,
            "coordinates" => [coordinates[1]]
        }
    }
    navPlace = {
        "id" => "#{@url_manifest}/#{@library}/#{@uuid}/feature",
        "type" => "FeatureCollection",
        "features" => [features]
    }
    return navPlace
end
def parse_coordinates
    input = @mods["coordinates"]
    reg1 = "^\\((\\d{1,3})°(\\d{1,2})\´(\\d{1,2})\"\\s([v,z]{1})\.d\.--(\\d{1,3})°(\\d{1,2})\´(\\d{1,2})\"\\s([v,z]{1})\.d\.\/(\\d{1,3})°(\\d{1,2})\´(\\d{1,2})\"\\s([s,j]{1})\.š\.--(\\d{1,3})°(\\d{1,2})\´(\\d{1,2})\"\\s([s,j]{1})\.š\.\\)$"
    reg2 = "^\\(([E,W]{1})\\s(\\d{1,3})°(\\d{1,2})'(\\d{1,2})\"--([E,W]{1})\\s(\\d{1,3})°(\\d{1,2})'(\\d{1,2})\"\/([S,N]{1})\\s(\\d{1,3})°(\\d{1,2})'(\\d{1,2})\"--([S,N]{1})\\s(\\d{1,3})°(\\d{1,2})'(\\d{1,2})\"\\)$"
    output = input.match(reg1)
    output2 = input.match(reg2)
    # d1s1 d2s1
    # d1s2 d2s2
    if !output.nil?
        d1x = output[4]
        d1 = (output[1].to_f + (output[2].to_f/60) + (output[3].to_f/3600))
        d2x = output[8]
        d2 = (output[5].to_f + (output[6].to_f/60) + (output[7].to_f/3600))
        s1x = output[12]
        s1 = (output[9].to_f + (output[10].to_f/60) + (output[11].to_f/3600))
        s2x = output[16]
        s2 = (output[13].to_f + (output[14].to_f/60) + (output[15].to_f/3600))
        if d1x == "z"
            d1 = d1*-1
        end
        if d2x == "z"
            d2 = d2*-1
        end
        if s1x == "j"
            s1 = s1*-1
        end
        if s2x == "j"
            s2 = s2*-1
        end
        if (d1 == d2 && s1 == s2)
            coordinates = ["Point", [d1, s1]]
        else
            coordinates = ["Polygon", [[d1, s1], [d2, s1], [d1, s2], [d2, s2]]]
        end
    elsif !output2.nil?
        d1x = output2[1]
        d1 = (output2[2].to_f + (output2[3].to_f/60) + (output2[4].to_f/3600))
        d2x = output2[5]
        d2 = (output2[6].to_f + (output2[7].to_f/60) + (output2[8].to_f/3600))
        s1x = output2[9]
        s1 = (output2[10].to_f + (output2[11].to_f/60) + (output2[12].to_f/3600))
        s2x = output2[13]
        s2 = (output2[14].to_f + (output2[15].to_f/60) + (output2[16].to_f/3600))
        if d1x == "W"
            d1 = d1*-1
        elsif d2x == "W"
            d2 = d2*-1
        elsif s1x == "S"
            s1 = s1*-1
        elsif s2x == "S"
            s2 = s2*-1
        end
        if (d1 == d2 && s1 == s2)
            coordinates = ["Point", [d1, s1]]
        else
            coordinates = ["Polygon", [[d1, s1], [d2, s1], [d1, s2], [d2, s2]]]
        end
    end
    return coordinates
end

def create_list_of_pages(uuid)
    # nactu a seradim si stranky  
    solr_request = "#{@kramerius}#{@solr_url}fl=#{@pid},#{@details},#{@rels_ext_index},#{@fedora_model}&q=#{@parent_pid}:#{uuid} AND #{@fedora_model}:page&rows=1500&start=0"
    object = get_json(solr_request)
    response_body = object["response"]["docs"]
    sorted_object = response_body.sort { |a, b| a[@rels_ext_index] <=> b[@rels_ext_index]}
    pids = []
    pages = []

    # pro kazdou stranku:
    sorted_object.each do |page|
        page_properties = {}
        uuid_page = page[@pid]
        pids.push(uuid_page)
        page_properties["pid"] = uuid_page
        index = @canvasIndex

        # K7 ALTO https://k7-test.mzk.cz/search/api/client/v7.0/items/uuid:52d55705-435f-11dd-b505-00145e5790ea/ocr/alto
        # K7 THUMB https://k7-test.mzk.cz/search/api/client/v7.0/items/uuid:52d55705-435f-11dd-b505-00145e5790ea/image/thumb
        # K7 IMAGE https://k7-test.mzk.cz/search/api/client/v7.0/items/uuid:52d55705-435f-11dd-b505-00145e5790ea/image/

        # id pro vsechny urovne
        page_properties["canvas_id"] = "#{@url_manifest}/#{@library}/#{@uuid}/canvases/#{index}"
        page_properties["annotationPage_id"] = "#{@url_manifest}/#{@library}/#{@uuid}/canvases/#{index}/ap"
        page_properties["annotation_id"] = "#{@url_manifest}/#{@library}/#{@uuid}/canvases/#{index}/ap/a"
        if @v == "k5"
            page_properties["body_id_iiif"] = "#{@kramerius}/search/iiif/#{uuid_page}/full/full/0/default.jpg"
            page_properties["body_id_imgfull"] = "#{@kramerius}#{@items}#{uuid_page}/streams/IMG_FULL"
            page_properties["alto_id"] = "#{@kramerius}#{@items}#{uuid_page}#{@alto_url}"
            page_properties["thumb_id"] = "#{@kramerius}#{@items}#{uuid_page}/thumb"
        elsif @v == "k7"
            # PROVERIT!
            page_properties["body_id_iiif"] = "#{@kramerius}/search/iiif/#{uuid_page}/full/full/0/default.jpg"
            page_properties["body_id_imgfull"] = "#{@kramerius}#{@items}#{uuid_page}/image"
            page_properties["alto_id"] = "#{@kramerius}#{@items}#{uuid_page}#{@alto_url}"
            page_properties["thumb_id"] = "#{@kramerius}#{@items}#{uuid_page}/image/thumb"
        end

        # cislo strany # typ strany

        if @v == "k5"
            if !page["details"][0].split("##")[0].nil?
                page_properties["page_number"] = page["details"][0].split("##")[0].strip.sub(" ", "")
                page_properties["label"] = page_properties["page_number"]
            end
            if !page["details"][0].split("##")[1].nil?
                page_properties["page_type"] = page["details"][0].split("##")[1].strip.sub(" ", "")
                page_properties["label"] = page_properties["label"] + ' (' + page_properties["page_type"] + ')'
            end
        elsif @v == "k7"
            if !page["page.number"].nil?
                page_properties["page_number"] = page["page.number"]
                page_properties["label"] = page_properties["page_number"]
            end
            if !page["page.type"].nil?
                page_properties["page_type"] = page["page.type"]
                page_properties["label"] = page_properties["label"] + ' (' + page_properties["page_type"] + ')'
            end
        end
        
        pages.push(page_properties)
        @canvasIndex += 1
    end
 
    if !pages[0].nil?
        # PROVERIT!
        image_iiif_control = control_404("#{@kramerius}/search/iiif/#{pages[0]['pid']}/info.json") 
        iiif_image_body_control = !(get_xml("#{@kramerius}/search/iiif/#{pages[0]['pid']}/info.json") == '')
        @image_iiif = image_iiif_control && iiif_image_body_control
        # @first_page_width = get_json("#{@kramerius}/search/iiif/#{pages[0]['pid']}/info.json")["width"]
        # @first_page_height = get_json("#{@kramerius}/search/iiif/#{pages[0]['pid']}/info.json")["height"]
    end

    # typhoeus test
    if @full
        if @image_iiif
            hydra = Typhoeus::Hydra.new(max_concurrency: 10)
            requests = pids.map{ |pid|
                request = Typhoeus::Request.new( 
                    "#{@kramerius}/search/iiif/#{pid}/info.json",
                    method: :get,
                    headers: {
                        "Content-Type" => "application/json"
                    },
                    followlocation: true
                )
                hydra.queue(request)
                request
            }
            # puts 'before hydra run', Time.new
            hydra.run
            # puts 'after hydra run', Time.new
            responses = requests.map { |request|
                JSON(request.response.body) if request.response.code === 200
            }
            # puts responses
            pages.each do |page|
                pid2 = "#{@kramerius}/search/iiif/#{page["pid"]}"
                if !responses.nil?
                    responses.each do |item|
                        if !item.nil?
                            if pid2 === item["@id"]
                                page["width"] = item["width"]
                                page["height"] = item["height"]
                                # page["thumb_width"] = item["sizes"][0]["width"]
                                # page["thumb_height"] = item["sizes"][0]["height"]
                                # page["width"] = item["sizes"][item["sizes"].length - 1]["width"]
                                # page["height"] = item["sizes"][item["sizes"].length - 1]["height"]
                            end
                        end
                    end
                end
            end
        else
            pages.each do |page| 
                page["width"] = 1000
                page["height"] = 1000
            end
        end
    else
        pages.each do |page|
            # if @image_iiif
            #     page["width"] = @first_page_width
            #     page["height"] = @first_page_height
            # else
                page["width"] = 1000
                page["height"] = 1000
            # end
        end
    end

    return pages
end

def create_items_pages(uuid)
    uuid = uuid.to_json
    itemsCanvas = []
    pages = create_list_of_pages(uuid)
    if !pages[0].nil?
        alto = control_404("#{@kramerius}#{@items}#{pages[0]['pid']}#{@alto_url}")
    end
    pages.each do |page|
        itemsAnnotationPage = []
        itemsAnnotation = []
        seeAlso = {"id" => page["alto_id"], 
                "type" => "Alto", 
                "profile" => "https://www.loc.gov/standards/alto/v3/alto.xsd", 
                "label" => { "none" => ["ALTO XML"] }, 
                "format" => "text/xml"}
        body_service = {"@id" => "#{@kramerius}/search/iiif/#{page["pid"]}",
                        "@type" => "ImageService2",
                        "profile" => "http://iiif.io/api/image/2/level1.json",
                        }
        # TODO thumb_service = {}
        canvas_thumbnail = {"id" => page["thumb_id"], 
                            "type" => "Image", 
                            # TODO "service" => [thumb_service]
                            }
        if @image_iiif
            # PROVERIT
            body = {"id" => page["body_id_iiif"], 
                "type" => "Image", 
                "format" => "image/jpeg",
                "width" => page["width"],
                "height" => page["height"],
                "service" => [body_service]
                }
        else 
            body = {"id" => page["body_id_imgfull"], 
                "type" => "Image", 
                "format" => "image/jpeg", 
                }
        end
        annotation = {"id" => page["annotation_id"], 
                      "type" => "Annotation", 
                      "motivation" => "painting", 
                      "body" => body, 
                      "target" => page["canvas_id"]
                    }
        annotationPage = {"id" => page["annotationPage_id"], 
                          "type" => "AnnotationPage", 
                          "items" => itemsAnnotation
                        }
        if alto
            canvas = {"id" => page["canvas_id"], 
                      "type" => "Canvas", 
                      "label" => { "none" => [page["label"]]}, 
                      "width" => page["width"],
                      "height" => page["height"],
                      "thumbnail" => [canvas_thumbnail], 
                      "seeAlso" => [seeAlso], 
                      "items" => itemsAnnotationPage
                    }
        else
            canvas = {"id" => page["canvas_id"], 
                "type" => "Canvas", 
                "label" => { "none" => [page["label"]]}, 
                "width" => page["width"],
                "height" => page["height"],
                "thumbnail" => [canvas_thumbnail],  
                "items" => itemsAnnotationPage
            }
        end
        itemsAnnotation.push(annotation)
        itemsAnnotationPage.push(annotationPage)
        itemsCanvas.push(canvas)
    end
    return itemsCanvas
end

def part_of
    uuid = "#{@uuid}".to_json
    solr_request = "#{@kramerius}#{@solr_url}fl=#{@parent_pid}&q=#{@pid}:#{uuid}&rows=1500&start=0"
    object = get_json(solr_request)
    response_body = object["response"]["docs"]
    partOf = []

    response_body.each do |a|
        if @v == "k5"
            a["#{@parent_pid}"].each do |pid|
                item = {}
                uuid = pid.to_json
                solr_request = "#{@kramerius}#{@solr_url}fl=#{@pid},#{@title_url},#{@root_title_url},#{@fedora_model}&q=#{@pid}:#{uuid}&rows=1500&start=0"
                object = get_json(solr_request)
                response_body = object["response"]["docs"][0]
                if response_body["#{@fedora_model}"] == "periodical" || response_body["#{@fedora_model}"] == "periodicalvolume" || response_body["#{@fedora_model}"] == "soundrecording" || response_body["#{@fedora_model}"] == "monograph"
                    item["id"] = "#{@url_manifest}/#{@library}/#{pid}"
                    item["type"] = "Collection"
                    item["label"] = response_body["#{@root_title_url}"]
                end
                partOf.push(item)
            end
        elsif @v == "k7"
            item = {}
            pid = a["#{@parent_pid}"]
            uuid = pid.to_json
            solr_request = "#{@kramerius}#{@solr_url}fl=#{@pid},#{@title_url},#{@root_title_url},#{@fedora_model}&q=#{@pid}:#{uuid}&rows=1500&start=0"
            object = get_json(solr_request)
            response_body = object["response"]["docs"][0]
            if response_body["#{@fedora_model}"] == "periodical" || response_body["#{@fedora_model}"] == "periodicalvolume" || response_body["#{@fedora_model}"] == "soundrecording" || response_body["#{@fedora_model}"] == "monograph"
                item["id"] = "#{@url_manifest}/#{@library}/#{pid}"
                item["type"] = "Collection"
                item["label"] = response_body["#{@root_title_url}"]
            end
            partOf.push(item)
        end
    end

    return partOf
end

# ---------- MONOGRAFIE -----------
def create_iiif_monograph
    if !@mods["coordinates"].nil?
        context = ["http://iiif.io/api/extension/navplace/context.json", "http://iiif.io/api/presentation/3/context.json"]
        iiif = {"@context" => context, 
            "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
            "type" => "Manifest", 
            "label" => create_label(@uuid), 
            "metadata" => create_metadata, 
            # "behavior" => create_behavior, 
            "provider" => create_provider(@library)[0], 
            "homepage" => create_homepage,
            "thumbnail" => create_thumbnail(@uuid),
            "navPlace" => create_navPlace,
            "items" => create_items_pages(@uuid)
        }
    elsif @document_model == 'monographunit'
        context = "https://iiif.io/api/presentation/3/context.json"
        iiif = {"@context" => context, 
            "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
            "type" => "Manifest", 
            "label" => create_label(@uuid), 
            "metadata" => create_metadata, 
            # "behavior" => create_behavior, 
            "provider" => create_provider(@library)[0], 
            "homepage" => create_homepage,
            "thumbnail" => create_thumbnail(@uuid),
            "items" => create_items_pages(@uuid),
            "partOf" => part_of
        }
    else
        context = "https://iiif.io/api/presentation/3/context.json"
        iiif = {"@context" => context, 
            "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
            "type" => "Manifest", 
            "label" => create_label(@uuid), 
            "metadata" => create_metadata, 
            # "behavior" => create_behavior, 
            "provider" => create_provider(@library)[0], 
            "homepage" => create_homepage,
            "thumbnail" => create_thumbnail(@uuid),
            "items" => create_items_pages(@uuid)
        }
    end
    return JSON.pretty_generate(iiif)
end


# ---------- VICESVAZKY -----------
def create_iiif_monograph_collection
    context = "https://iiif.io/api/presentation/3/context.json"
    iiif = {"@context" => context, 
        "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
        "type" => "Collection", 
        "label" => create_label(@uuid), 
        "metadata" => create_metadata, 
        # "behavior" => create_behavior, 
        "provider" => create_provider(@library)[0], 
        "homepage" => create_homepage,
        "thumbnail" => create_thumbnail(@uuid),
        "items" => create_items_monographunits
    }
    return JSON.pretty_generate(iiif)
end

def create_items_monographunits
    itemsMonographunits= []
    monographunits = create_list_of_monographunits

    monographunits.each do |monographunit|
        item = {"id" => "#{@url_manifest}/#{@library}/#{monographunit["pid"]}",
                "type" => "Manifest",
                "label" => "#{monographunit["label"]} "
               }
        itemsMonographunits.push(item)
    end
    return itemsMonographunits
end

def create_list_of_monographunits
    uuid = "#{@uuid}".to_json
    object = get_json("#{@kramerius}#{@solr_url}fl=#{@pid},#{@details},#{@rels_ext_index},#{@fedora_model},#{@title_url}&q=#{@parent_pid}:#{uuid} AND #{@fedora_model}:monographunit&rows=1500&start=0")
    response_body = object["response"]["docs"]
    sorted_object = response_body.sort { |a, b| a["#{@rels_ext_index}"] <=> b["#{@rels_ext_index}"]}

    monographunits = []

    sorted_object.each do |monographunit|
        monographunit_properties = {}
        monographunit_properties["index"] = monographunit["#{@rels_ext_index}"][0]
        monographunit_properties["pid"] = monographunit["#{@pid}"]
        if @v == "k5"
            if !monographunit["details"][0].split("##")[0].nil?
                monographunit_properties["number"] = monographunit["details"][0].split("##")[0].strip.sub(" ", "")
                monographunit_properties["label"] = monographunit_properties["number"]
            end
            if !monographunit["details"][0].split("##")[1].nil?
                monographunit_properties["title"] = monographunit["details"][0].split("##")[1].strip.sub(" ", "")
                monographunit_properties["label"] = monographunit_properties["label"] + '. ' + monographunit_properties["title"]
            end
        elsif @v == "k7"
            if !monographunit["part.number.str"].nil?
                monographunit_properties["number"] = monographunit["part.number.str"]
                
            end
            if !monographunit["part.name"].nil?
                monographunit_properties["title"] = monographunit["part.name"]
                monographunit_properties["label"] = monographunit["part.name"]
            end
        end
        monographunits.push(monographunit_properties)
    end

    return monographunits

end


# ---------- CISLO PERIODIKA -----------

def create_iiif_periodicalissue
    iiif = {"@context" => "https://iiif.io/api/presentation/3/context.json", 
                "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
                "type" => "Manifest", 
                "label" => create_label(@uuid), 
                "metadata" => create_metadata, 
                "provider" => create_provider(@library)[0],
                "homepage" => create_homepage_periodical_volume_issue,
                "thumbnail" => create_thumbnail(@uuid),
                "items" => create_items_pages(@uuid),
                "partOf" => part_of
            }
    return JSON.pretty_generate(iiif)
end

# ---------- ROCNIK PERIODIKA -----------

def create_iiif_periodicalvolume
    iiif = {"@context" => "https://iiif.io/api/presentation/3/context.json", 
                "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
                "type" => "Collection", 
                "label" => create_label(@uuid), 
                "metadata" => create_metadata, 
                "provider" => create_provider(@library)[0], 
                "homepage" => create_homepage_periodical_volume_issue,
                "thumbnail" => create_thumbnail(@uuid),
                "items" => create_items_periodical_issues,
                "partOf" => part_of
            }
    return JSON.pretty_generate(iiif)
end

def create_list_of_periodical_issues
    uuid = "#{@uuid}".to_json

    # najdu si cisla (items) a seradim  
    object = get_json("#{@kramerius}#{@solr_url}fl=#{@pid},#{@details},#{@rels_ext_index}&q=#{@parent_pid}:#{uuid} AND #{@fedora_model}:periodicalitem&rows=1500&start=0")
    response_body = object["response"]["docs"]
    sorted_object = response_body.sort { |a, b| a["#{@rels_ext_index}"] <=> b["#{@rels_ext_index}"]}
    
    periodicalissues = []

    sorted_object.each do |issue|
        issue_properties = {}
        issue_properties["index"] = issue["#{@rels_ext_index}"][0]
        issue_properties["pid"] = issue["#{@pid}"]    
        # datum vydani cisla # cislo cisla
        if @v == "k5"
            if !issue["details"][0].split("##")[0].nil?
                issue_properties["issue_date"] = issue["details"][0].split("##")[2].strip.sub(" ", "")
            end  
            if !issue["details"][0].split("##")[1].nil?
                issue_properties["issue_number"] = issue["details"][0].split("##")[3].strip.sub(" ", "")
            end
        elsif @v == "k7"
            if !issue["date.str"].nil?
                issue_properties["issue_date"] = issue["date.str"]
            end
            if !issue["part.number.str"].nil?
                issue_properties["issue_number"] = issue["part.number.str"]
            end
        end
        periodicalissues.push(issue_properties)
    end
    return periodicalissues
end

def create_items_periodical_issues
    itemsIssues = []
    issues = create_list_of_periodical_issues

    issues.each do |issue|
        item = {"id" => "#{@url_manifest}/#{@library}/#{issue["pid"]}",
                "type" => "Manifest",
                "label" => "#{@root_title} (#{issue["issue_date"]})"
               }
        itemsIssues.push(item)
    end
    return itemsIssues
end


# ---------- TITUL PERIODIKA -----------
def create_iiif_periodical
    iiif = {"@context" => "https://iiif.io/api/presentation/3/context.json",
                "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
                "type" => "Collection", 
                "label" => create_label(@uuid), 
                "metadata" => create_metadata, 
                "provider" => create_provider(@library)[0], 
                "homepage" => create_homepage,
                "thumbnail" => create_thumbnail(@uuid),
                "items" => create_items_periodical_volumes
            }
    return JSON.pretty_generate(iiif)
end
def create_list_of_periodical_volumes
    uuid = "#{@uuid}".to_json

    # najdu si rocniky a seradim   
    object = get_json("#{@kramerius}#{@solr_url}fl=#{@pid},#{@details},#{@rels_ext_index}&q=#{@parent_pid}:#{uuid} AND #{@fedora_model}:periodicalvolume&rows=1500&start=0")
    response_body = object["response"]["docs"]
    sorted_object = response_body.sort { |a, b| a["#{@rels_ext_index}"] <=> b["#{@rels_ext_index}"]}
    
    periodicalvolumes = []

    sorted_object.each do |volume|
        volume_properties = {}
        volume_properties["index"] = volume["#{@rels_ext_index}"][0]
        volume_properties["pid"] = volume["#{@pid}"]
        # cislo rocniku # rok vydani rocniku
        if @v == "k5"
            if !volume["details"][0].split("##")[0].nil?
                volume_properties["volume_date"] = volume["details"][0].split("##")[0].strip.sub(" ", "")
            end  
            if !volume["details"][0].split("##")[1].nil?
                volume_properties["volume_number"] = volume["details"][0].split("##")[1].strip.sub(" ", "")
            end
        elsif @v == "k7"
            if !volume["date.str"].nil?
                volume_properties["volume_date"] = volume["date.str"]
            end
            if !volume["part.number.str"].nil?
                volume_properties["volume_number"] = volume["part.number.str"]
            end
        end
        periodicalvolumes.push(volume_properties)
    end
    return periodicalvolumes
end

def create_items_periodical_volumes
    itemsVolumes = []
    volumes = create_list_of_periodical_volumes
    volumes.each do |volume|
        item = {"id" => "#{@url_manifest}/#{@library}/#{volume["pid"]}",
                "type" => "Collection",
                "label" => volume["volume_date"]
               }
        itemsVolumes.push(item)
    end
    return itemsVolumes
end

# ---------- SOUNDRECORDING -----------
def create_iiif_soundrecording
    @soundrecordings = create_items_soundrecording
    iiif = {"@context" => "https://iiif.io/api/presentation/3/context.json",
                "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
                "type" => "Manifest", 
                "label" => create_label(@uuid), 
                "metadata" => create_metadata, 
                "provider" => create_provider(@library)[0], 
                "homepage" => create_homepage,
                "thumbnail" => create_thumbnail(@uuid),
                "items" => @soundrecordings[0],
                "structures" => @soundrecordings[1]
            }
    return JSON.pretty_generate(iiif)
end
def create_items_soundrecording
    @rangeIndex = 0
    canvases = create_items_pages(@uuid)
    structures = []
    soundunits = create_list_of_soundunits
    soundunits.each do |soundunit|
        su_uuid = soundunit["pid"]
        index = @rangeIndex
        range_items = []
        # soundunit_pages = create_items_pages(su_uuid)
        # soundunit_pages.each do |page|
        #     canvases.push(page)
        #     range_canvas = {"id" => page["id"], "type" => page["type"]}
        #     range_items.push(range_canvas)
        # end
        soundunit_mp3s = create_items_tracks(su_uuid)
        soundunit_mp3s.each do |mp3|
            canvases.push(mp3)
            range_canvas = {"id" => mp3["id"], "type" => mp3["type"]}
            range_items.push(range_canvas)
        end
        range = {"id" => "#{@url_manifest}/#{@library}/#{@uuid}/range/#{index}",
                 "type" => "Range",
                 "label" => "#{soundunit["title"]}",
                 "items" => range_items
                }
        @rangeIndex += 1
        structures.push(range)
    end
    return [canvases, structures]
end
def create_list_of_soundunits
    uuid = "#{@uuid}".to_json
    object_soundunit = get_json("#{@kramerius}#{@solr_url}fl=#{@pid},#{@details},#{@rels_ext_index},#{@title_url}&q=#{@parent_pid}:#{uuid} AND #{@fedora_model}:soundunit&rows=1500&start=0")
    soundunit_body = object_soundunit["response"]["docs"]
    soundunits = []
    soundunit_body.each do |soundunit|
        soundunit_properties = {}
        soundunit_properties["pid"] = soundunit["#{@pid}"]
        soundunit_properties["title"] = soundunit["#{@title_url}"]
        soundunits.push(soundunit_properties)
    end
    return soundunits
end
def create_list_of_mp3(uuid)
    # TODO DURATION AZ OD VERZE API K7
    uuid2 = uuid.to_json
    object = get_json("#{@kramerius}#{@solr_url}fl=#{@pid},#{@details},#{@rels_ext_index},#{@title_url},track.length&q=#{@parent_pid}:#{uuid2} AND #{@fedora_model}:track&rows=1500&start=0")
    response_body = object["response"]["docs"]
    tracks = []
    
    response_body.each do |track|
        index = @canvasIndex
        track_properties = {}
        track_properties["title"] = track["#{@title_url}"]
        track_properties["uuid"] = track["#{@pid}"]
        # PROVERIT
        if !track["track.length"].nil?
            track_properties["duration"] = track["track.length"].to_f
        else
            track_properties["duration"] = 1.to_f
        end
        track_properties["thumb_id"] = "#{@kramerius}#{@items}#{uuid}#{@thumb_url}"
        #K7 https://k7-test.mzk.cz/search/api/client/v7.0/items/uuid:a31525ae-4033-4242-9293-d38c1ef06c61/audio/mp3
        track_properties["body_id"] = "#{@kramerius}#{@items}#{track["#{@pid}"]}#{@mp3_url}"
        track_properties["canvas_id"] = "#{@url_manifest}/#{@library}/#{@uuid}/canvases/#{index}"
        track_properties["annotationPage_id"] = "#{@url_manifest}/#{@library}/#{@uuid}/canvases/#{index}/ap"
        track_properties["annotation_id"] = "#{@url_manifest}/#{@library}/#{@uuid}/canvases/#{index}/ap/a"
        @canvasIndex += 1
        tracks.push(track_properties)     
    end   
    return tracks
end
def create_items_tracks(uuid)
    itemsCanvas = []
    tracks = create_list_of_mp3(uuid)
    tracks.each do |track|
        itemsAnnotationPage = []
        itemsAnnotation = []
        track_thumbnail = {"id" => track["thumb_id"],
                            "type" => "Image",
                            "format" => "image/jpeg"
                            }
        body = {"id" => track["body_id"], 
            "type" => "Sound", 
            #TODO "duration" => track["duration"],
            "duration" => track["duration"],
            "format" => "audio/mp3", 
            #TODO "service" => [body_service]
            }
        annotation = {"id" => track["annotation_id"], 
            "type" => "Annotation", 
            "motivation" => "painting", 
            "body" => body, 
            "target" => track["canvas_id"]
        }
        annotationPage = {"id" => track["annotationPage_id"], 
            "type" => "AnnotationPage", 
            "items" => itemsAnnotation
        }
        canvas = {"id" => track["canvas_id"], 
            "type" => "Canvas", 
            "label" => { "none" => [track["title"]]},
            "duration" => track["duration"],
            "thumbnail" => [track_thumbnail], 
            # "seeAlso" => [seeAlso], 
            "items" => itemsAnnotationPage
        }
        itemsAnnotation.push(annotation)
        itemsAnnotationPage.push(annotationPage)
        itemsCanvas.push(canvas)
    end
    return itemsCanvas
end

# ---------- CREATE IIIF KONVOLUT ----------- 
def create_iiif_konvolut
    iiif = {"@context" => "https://iiif.io/api/presentation/3/context.json",
        "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
        "type" => "Collection", 
        "label" => create_label(@uuid), 
        "metadata" => create_metadata, 
        "provider" => create_provider(@library)[0], 
        "homepage" => create_homepage,
        "thumbnail" => create_thumbnail(@uuid),
        "items" => create_items_konvolut
    }
    return JSON.pretty_generate(iiif)
end
def create_items_konvolut
    itemsKonvolut = []
    konvolut_parts = create_list_of_konvolut_parts
    konvolut_parts.each do |part|
        item = {"id" => "#{@url_manifest}/#{@library}/#{part["pid"]}",
                "type" => "Manifest",
                "label" => part["label"]
               }
        itemsKonvolut.push(item)
    end
    return itemsKonvolut
end
def create_list_of_konvolut_parts
    uuid = "#{@uuid}".to_json

    # najdu si casti konvolutu a seradim   
    object = get_json("#{@kramerius}#{@solr_url}fl=#{@pid},#{@fedora_model},#{@rels_ext_index},#{@title_url}&q=#{@parent_pid}:#{uuid}&rows=1500&start=0")
    response_body = object["response"]["docs"]
    sorted_object = response_body.sort { |a, b| a["#{@rels_ext_index}"] <=> b["#{@rels_ext_index}"]}
    
    konvolutparts = []

    sorted_object.each do |part|
        if !(part["#{@fedora_model}"] == "convolute")
            part_properties = {}
            part_properties["index"] = part["#{@rels_ext_index}"][0]
            part_properties["pid"] = part["#{@pid}"]
            part_properties["label"] = part["#{@title_url}"]
            konvolutparts.push(part_properties)
        end
    end
    return konvolutparts
end
# ---------- CREATE IIIF COLLECTION ----------- 
def create_iiif_collection
    iiif = {"@context" => "https://iiif.io/api/presentation/3/context.json",
        "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
        "type" => "Collection", 
        "label" => create_label(@uuid), 
        "metadata" => create_metadata, 
        "provider" => create_provider(@library)[0], 
        "homepage" => create_homepage,
        "thumbnail" => create_thumbnail(@uuid),
        "items" => create_items_collection
    }
    return JSON.pretty_generate(iiif)
end
def create_items_collection
    itemsCollection = []
    collection_parts = create_list_of_collection_parts
    collection_parts.each do |part|
        if part["#{@fedora_model}"] == "collection"
            item = {"id" => "#{@url_manifest}/#{@library}/#{part["pid"]}",
                    "type" => "Collection",
                    "label" => part["label"]
                }
                itemsCollection.push(item)
        else
            item = {"id" => "#{@url_manifest}/#{@library}/#{part["pid"]}",
                    "type" => "Manifest",
                    "label" => part["label"]
                }
                itemsCollection.push(item)
        end
    end
    return itemsCollection
end
def create_list_of_collection_parts
    uuid = "#{@uuid}".to_json
    # najdu si casti kolekce a seradim  
    #  https://k7-test.mzk.cz/search/api/client/v7.0/search?q=*:*&fq=(in_collections.direct:%22uuid:7dfc3afb-66ae-45e9-8a0e-1f8d5b54a775%22)&fl=pid,accessibility,model,authors,titles.search,title.search,root.title,date.str,collection.desc&facet=true&facet.mincount=1&facet.field=keywords.facet&facet.field=languages.facet&facet.field=physical_locations.facet&facet.field=geographic_names.facet&facet.field=authors.facet&facet.field=publishers.facet&facet.field=publication_places.facet&facet.field=own_model_path&facet.field=genres.facet&facet.field=accessibility&sort=created%20desc&rows=60&start=0 
    object = get_json("#{@kramerius}#{@solr_url}fl=#{@pid},#{@parent_pid},#{@fedora_model},#{@title_url},#{@root_title_url}&q=*:*&fq=(in_collections.direct:#{uuid})&rows=1500&start=0")
    response_body = object["response"]["docs"]
    
    collectionparts = []

    response_body.each do |part|
        if !(part["#{@fedora_model}"] == "page")
            part_properties = {}
            part_properties["pid"] = part["#{@pid}"]
            part_properties["label"] = part["#{@root_title_url}"]
            collectionparts.push(part_properties)
        else
            part_properties = {}
            part_properties["pid"] = part["#{@parent_pid}"]
            part_properties["label"] = part["#{@root_title_url}"] + " (strana " + part["#{@title_url}"] + ")"
            collectionparts.push(part_properties)
        end
    end
    return collectionparts
end
# ---------- CREATE IIIF PDF ---------------

def create_iiif_pdf
    iiif = {"@context" => "https://iiif.io/api/presentation/3/context.json",
        "id" => "#{@url_manifest}/#{@library}/#{@uuid}", 
        "type" => "Manifest", 
        "label" => create_label(@uuid), 
        "metadata" => create_metadata, 
        "provider" => create_provider(@library)[0], 
        "homepage" => create_homepage,
        "thumbnail" => create_thumbnail(@uuid),
        "rendering" => create_rendering(@uuid),
        # "items" => create_items_pages(@uuid)
    }
    return JSON.pretty_generate(iiif)
end

def create_rendering(uuid)
    rendering = []
    # https://kramerius.mzk.cz/search/api/v5.0/item/uuid:27055693-9413-43d3-8e61-d5048b14dd16/streams/IMG_FULL
    pdf_properties = {
        "id" => "#{@kramerius}#{@items}#{@uuid}/streams/IMG_FULL",
        "type" => "Text",
        "label" => { "en": [ "PDF" ] },
        "format" => "application/pdf"
    }
    rendering.push(pdf_properties)
end

# ---------- CREATE IIIF MANIFEST ----------- 
def create_iiif
    @kramerius = create_provider(@library)[1]
    version = create_provider(@library)[2]
    @v = "k" + version.to_s.split(".")[0]
    @document = find_document_properties(@uuid)
    @mods = mods_extractor
    @document_model = @document["model"]
    @root_title = @document["root_title"]
    @title = @document["title"]
    if @document_model == "monograph"
        @type_of_resource = "Monografie"  
        puts create_iiif_monograph
    elsif @document_model == "monographcollection"
        @type_of_resource = "Sbírka monografií"
        puts create_iiif_monograph_collection
    elsif @document_model == "monographunit"
        @type_of_resource = "Monografie - část"
        puts create_iiif_monograph
    elsif @document_model == "map"
        @type_of_resource = "Mapa"
        puts create_iiif_monograph
    elsif @document_model == "graphic"
        @type_of_resource = "Grafika"
        puts create_iiif_monograph
    elsif @document_model == "sheetmusic"
        @type_of_resource = "Hudebnina"
        puts create_iiif_monograph
    elsif @document_model == "archive"
        @type_of_resource = "Archiválie"
        puts create_iiif_monograph
    elsif @document_model == "manuscript"
        @type_of_resource = "Rukopis"
        puts create_iiif_monograph
    elsif @document_model == "soundrecording"
        @type_of_resource = "Zvuková nahrávka"
        puts create_iiif_soundrecording
    elsif @document_model == "soundunit"
        @type_of_resource = "Zvuková nahrávka - část"
        puts "soundunit - negeneruje iiif"
    elsif @document_model == "periodical"
        @type_of_resource = "Periodikum"
        puts create_iiif_periodical
    elsif @document_model == "periodicalvolume"
        @type_of_resource = "Ročník periodika"
        puts create_iiif_periodicalvolume
    elsif @document_model == "periodicalitem"
        @type_of_resource = "Číslo periodika"
        puts create_iiif_periodicalissue
    elsif @document_model == "convolute"
        @type_of_resource = "Konvolut"
        puts create_iiif_konvolut
    elsif @document_model == "oldprintsheetmusic"
        @type_of_resource = "oldprintsheetmusic"
        puts create_iiif_monograph
    elsif @document_model == "collection"
        @type_of_resource = "Sbírka"
        puts create_iiif_collection
    elsif @document_model == "pdf"
        @type_of_resource = "PDF"
        puts create_iiif_pdf
    elsif @document_model.nil?
        puts '{ "error": "Zadané uuid v digitální knihovně ' + @library + ' neexistuje" }'
    else puts '{ "error": " Pro model ' + @document_model + ' nelze vygenerovat IIIF Presentation API v3. Zkuste uuid nadřazeného objektu." }'
    end
end

puts create_iiif