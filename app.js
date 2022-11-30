const express = require('express');
const app = express();
const https = require('https');
module.exports = app;

//---------------- HEADER - CORS ---------------------------------

app.use((req, res, next) => {
    res.setHeader("Access-Control-Allow-Origin", "*");
    res.setHeader(
        "Access-Control-Allow-Headers", 
        "Origin, X-Requested-With, Content-Type, Accept"
        )
    res.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
    next();
})
app.use(express.static('public'))

//----------------------------------------------------------------

const { spawn } = require('child_process');
const { Http2ServerRequest } = require('http2');

app.get("/:library/:uuid/:full?", (req, res, next) => {

    // const uuid = 'uuid:5ef70cb8-6398-4486-b5f7-94ba3150a052';
    const uuid = req.params.uuid;
    const library = req.params.library;
    const full = req.params.full;
    const ruby = spawn('ruby', ['iiif.rb', library, uuid, full]);
    console.log(full)

    let result = ""
    ruby.stdout.on('data', (data) => {
        result += data.toString();
    });

    ruby.on('close', (code) => {
        res.status(200).json(
            // message: 'success',
            JSON.parse(result)
        )
    });
    
});

app.get("/", (req, res, next) => {
    
    if (req.query.link) {
        const link = req.query.link;
        const ruby = spawn('ruby', ['iiif.rb', link]);
        
        let result = ""
        ruby.stdout.on('data', (data) => {
            result += data.toString();
        });
    
        ruby.on('close', (code) => {
            res.status(200).json(
                // message: 'success',
                JSON.parse(result)
            )
        });
    } else {
        https.get('https://raw.githubusercontent.com/moravianlibrary/Kramerius-IIIF-gateway/master/README.md', (resp) => {
            let data = '';
            resp.on('data', (bla) => {
                data += bla
            })
            resp.on('end', () => {
                // console.log((data));
                res.send(data)
              });
        })
        
        
    }

});




// app.get("/api2/iiif/:uuid/manifest.json", (req, res, next) => {

//     // const uuid = 'uuid:5ef70cb8-6398-4486-b5f7-94ba3150a052';
//     const uuid = req.params.uuid;
//     const ruby = spawn('ruby', ['iiif.rb', uuid]);

//     let result = ""
//     ruby.stdout.on('data', (data) => {
//         result += data.toString();
//     });

//     ruby.on('close', (code) => {
//         res.status(200).json(JSON.parse(result))
//     });
    
// });


