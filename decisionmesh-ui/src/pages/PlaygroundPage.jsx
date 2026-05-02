import { useState } from "react";
import { Card, CardContent, Typography, TextField, Button, Alert, CircularProgress, Box } from "@mui/material";
import { submitIntent } from "../utils/api";

const DEFAULT_PAYLOAD = JSON.stringify({
  intentType: "chat",
  objective: {
    description: "Hello AI"
  },
  constraints: {
    maxRetries: 3,
    timeoutSeconds: 30
  },
  budget: {
    amount: 10.0,
    currency: "USD"
  }
}, null, 2);

// NOT async — React components must be synchronous
export default function PlaygroundPage({ keycloak }) {
  const [payload, setPayload]       = useState(DEFAULT_PAYLOAD);
  const [result, setResult]         = useState(null);
  const [error, setError]           = useState(null);
  const [loading, setLoading]       = useState(false);
  const [parseError, setParseError] = useState(null);

  const handlePayloadChange = (e) => {
    setPayload(e.target.value);
    try {
      JSON.parse(e.target.value);
      setParseError(null);
    } catch {
      setParseError("Invalid JSON");
    }
  };

  const handleSubmit = async () => {
    console.log('handleSubmit - keycloak:', keycloak);
    console.log('handleSubmit - authenticated:', keycloak?.authenticated);
    console.log('handleSubmit - token:', keycloak?.token?.substring(0, 30));

    setError(null);
    setResult(null);


    let json;
    try {
      json = JSON.parse(payload);
    } catch {
      setError("Invalid JSON — fix the payload before submitting");
      return;
    }

    setLoading(true);
    try {
      //  pass keycloak (not token) — api.js handles refresh internally
      const id = await submitIntent(keycloak, json);
      setResult(id);
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  //  check keycloak.authenticated, not token
  const authenticated = keycloak?.authenticated ?? false;

  return (
      <Card>
        <CardContent>
          <Typography variant="h6">Submit Intent</Typography>

          <TextField
              multiline
              rows={10}
              fullWidth
              sx={{ mt: 2, fontFamily: "monospace" }}
              value={payload}
              onChange={handlePayloadChange}
              error={!!parseError}
              helperText={parseError ?? "Edit the JSON payload above"}
              inputProps={{ style: { fontFamily: "monospace", fontSize: 13 } }}
          />

          <Box sx={{ mt: 2, display: "flex", alignItems: "center", gap: 2 }}>
            <Button
                variant="contained"
                onClick={handleSubmit}
                disabled={!authenticated || loading || !!parseError}
            >
              {loading ? <CircularProgress size={20} color="inherit" /> : "Submit"}
            </Button>

            {!authenticated && (
                <Typography variant="caption" color="error">
                  Not authenticated
                </Typography>
            )}
          </Box>

          {result && (
              <Alert severity="success" sx={{ mt: 2 }}>
                Intent submitted — ID: <strong>{result}</strong>
              </Alert>
          )}

          {error && (
              <Alert severity="error" sx={{ mt: 2 }}>
                {error}
              </Alert>
          )}
        </CardContent>
      </Card>
  );
}
