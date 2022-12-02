<div style="font-family:Arial;margin: 15px;">
  <h1>IIIF Presentation API Gateway</h1>
    <h2>How it works</h2>
    <ul>
      <li>the script generates IIIF Presentation API v3 with canvas sizes 1000x1000</li>
      <li>adding <b>/full</b> at the end of the query (not for ?link) will generate real image sizes (only for documents with images available through IIIF image API)</li>
      <li>works for libraries registered in https://registr.digitalniknihovna.cz (if the Kramerius listed there works) and mzkk7 to test the API of Kramerius version 7</li>
    </ul>
    <h2>URL format</h2>
    <h3>1. without real image sizes</h3>
    <p><code>https://iiif.digitalniknihovna.cz/kod_knihovny/uuid</code></p>
    <div><i>Example: https://iiif.digitalniknihovna.cz/mzk/uuid:ed255350-301b-11ed-be63-005056827e52</i></div>
    <h3>2. with real image sizes</h3>
    <p><code>https://iiif.digitalniknihovna.cz/kod_knihovny/uuid/full</code></p>
    <div><i>Example: https://iiif.digitalniknihovna.cz/mzk/uuid:ed255350-301b-11ed-be63-005056827e52/full</i></div>
    <h3>3. link to the digital library</h3>
    <p><code>https://iiif.digitalniknihovna.cz/?link='link_to_the_digital_library'</code></p>
    <div><i>Example:
        https://iiif.digitalniknihovna.cz/?link=https://www.digitalniknihovna.cz/mzk/view/uuid:6dfb1870-a657-11eb-94e5-005056827e52?page=uuid:d8ea53a7-278a-42ab-b05c-a4b6dc9e8450</i></div>
    <h2>Examples for different types of documents</h2>
    <p>
        <b>Monograph</b>
        <i><a href='https://iiif.digitalniknihovna.cz/mzk/uuid:ca15a389-e5eb-4c19-b0d0-f919066ef2ba'>https://iiif.digitalniknihovna.cz/mzk/uuid:ca15a389-e5eb-4c19-b0d0-f919066ef2ba</a></i>
    </p>
    <p>
        <b>Multiple volume monograph</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzkk7/uuid:7e11fe20-043e-11e5-95ff-5ef3fc9bb22f">https://iiif.digitalniknihovna.cz/mzkk7/uuid:7e11fe20-043e-11e5-95ff-5ef3fc9bb22f</a></i>
    </p>
    <p>
        <b>Periodical title</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:ae81bb20-435d-11dd-b505-00145e5790ea">https://iiif.digitalniknihovna.cz/mzk/uuid:ae81bb20-435d-11dd-b505-00145e5790ea</a></i>
    </p>
    <p>
        <b>Periodical volume</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:b2268013-435d-11dd-b505-00145e5790ea">https://iiif.digitalniknihovna.cz/mzk/uuid:b2268013-435d-11dd-b505-00145e5790ea</a></i>
    </p>
    <p>
        <b>Periodical item</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:ee18e055-435d-11dd-b505-00145e5790ea">https://iiif.digitalniknihovna.cz/mzk/uuid:ee18e055-435d-11dd-b505-00145e5790ea</a></i>
    </p>
    <p>
        <b>Graphic</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:e8933b86-fecc-45cc-962c-d22e9271eee4">https://iiif.digitalniknihovna.cz/mzk/uuid:e8933b86-fecc-45cc-962c-d22e9271eee4</a></i>
    </p>
    <p>
        <b>Map</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:ba4934d1-0a1e-4a01-a89d-c948477ca833">https://iiif.digitalniknihovna.cz/mzk/uuid:ba4934d1-0a1e-4a01-a89d-c948477ca833</a></i>
    </p>
    <p>
        <b>Sheet music</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:61f6fddf-27ca-4acc-a756-a2fedc6508b8">https://iiif.digitalniknihovna.cz/mzk/uuid:61f6fddf-27ca-4acc-a756-a2fedc6508b8</a></i>
    </p>
    <p>
        <b>Archival document</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:b85c556b-07e1-46c7-b342-9602009f1bd5">https://iiif.digitalniknihovna.cz/mzk/uuid:b85c556b-07e1-46c7-b342-9602009f1bd5</a></i>
    </p>
    <p>
        <b>Manuscript</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:5e0d749d-c560-4b8c-aae7-665cb952b655">https://iiif.digitalniknihovna.cz/mzk/uuid:5e0d749d-c560-4b8c-aae7-665cb952b655</a></i>
    </p>
    <p>
        <b>Sound recordings</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:4067ca61-96f7-48b6-9fa3-8a127a4df5e6">https://iiif.digitalniknihovna.cz/mzk/uuid:4067ca61-96f7-48b6-9fa3-8a127a4df5e6</a></i>
    </p>
    <p>
        <b>PDF - does not generate items, just metadata description and a link to the pdf</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzk/uuid:27055693-9413-43d3-8e61-d5048b14dd16">https://iiif.digitalniknihovna.cz/mzk/uuid:27055693-9413-43d3-8e61-d5048b14dd16</a></i>
    </p>
    <p>
        <b>Virtual collection (K7 only)</b>
        <i><a href="https://iiif.digitalniknihovna.cz/mzkk7/uuid:c524ed10-7ebb-4a7f-8b5b-e0a160c52d2c">https://iiif.digitalniknihovna.cz/mzkk7/uuid:c524ed10-7ebb-4a7f-8b5b-e0a160c52d2c</a></i>
    </p>
</div>
    
