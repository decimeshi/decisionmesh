import { useState } from "react";
import { Card, CardContent, Typography, TextField, Button } from "@mui/material";
import { getIntent } from "../utils/api";

export default function IntentViewerPage({ token }) {
  const [id, setId] = useState("");
  const [intent, setIntent] = useState(null);

  const fetchIntent = async () => {
    const data = await getIntent(token, id);
    setIntent(data);
  };

  return (
    <Card>
      <CardContent>
        <Typography variant="h6">Fetch Intent</Typography>
        <TextField fullWidth sx={{ mt: 2 }} label="Intent UUID"
          value={id}
          onChange={(e) => setId(e.target.value)}
        />
        <Button variant="contained" sx={{ mt: 2 }} onClick={fetchIntent}>
          Fetch
        </Button>
        {intent && (
          <pre style={{ marginTop: 20 }}>
            {JSON.stringify(intent, null, 2)}
          </pre>
        )}
      </CardContent>
    </Card>
  );
}
