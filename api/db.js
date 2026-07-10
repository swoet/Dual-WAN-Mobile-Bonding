// Universal Postgres Serverless Backend
const { Pool } = require('pg');

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: { rejectUnauthorized: false }
});

module.exports = async function handler(req, res) {
  const { appId, collection } = req.query;
  
  if (!appId || !collection) {
    return res.status(400).json({ error: "Missing appId or collection parameter" });
  }

  try {
    if (req.method === 'GET') {
      const { rows } = await pool.query(
        "SELECT id, data, created_at FROM multidollar_data WHERE app_id = $1 AND collection = $2 ORDER BY created_at DESC",
        [appId, collection]
      );
      return res.status(200).json(rows.map(r => ({ id: r.id, ...r.data, created_at: r.created_at })));
    }
    
    if (req.method === 'POST') {
      const data = req.body;
      const { rows } = await pool.query(
        "INSERT INTO multidollar_data (app_id, collection, data) VALUES ($1, $2, $3) RETURNING id",
        [appId, collection, data]
      );
      return res.status(201).json({ success: true, id: rows[0].id });
    }
    
    return res.status(405).json({ error: "Method not allowed" });
  } catch (err) {
    console.error(err);
    return res.status(500).json({ error: "Database query failed", details: err.message });
  }
};