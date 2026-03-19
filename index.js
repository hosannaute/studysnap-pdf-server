const express = require('express');
const multer = require('multer');
const pdfParse = require('pdf-parse');
const cors = require('cors');

const app = express();
const upload = multer({ storage: multer.memoryStorage() });

app.use(cors());

app.get('/', (req, res) => {
  res.json({ status: 'StudySnap PDF Server is running' });
});

app.post('/extract', upload.single('file'), async (req, res) => {
  try {
    if (!req.file) {
      return res.status(400).json({ error: 'No file received' });
    }

    const data = await pdfParse(req.file.buffer);
    const text = data.text;

    if (!text || text.trim().length < 20) {
      return res.status(422).json({
        error: 'No readable text found. Please use a typed PDF.'
      });
    }

    res.json({ success: true, text: text.trim() });

  } catch (err) {
    res.status(500).json({ error: 'Failed to parse PDF: ' + err.message });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log('Server running on port ' + PORT));
