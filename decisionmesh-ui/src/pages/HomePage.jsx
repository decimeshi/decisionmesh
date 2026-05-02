import { Box, Button, Typography, Card, CardContent } from "@mui/material";

export default function HomePage({ keycloak }) {

    return (
        <Box
            sx={{
                height: "100vh",
                display: "flex",
                justifyContent: "center",
                alignItems: "center"
            }}
        >
            <Card sx={{ width: 400, textAlign: "center", p: 2 }}>
                <CardContent>
                    <Typography variant="h5" gutterBottom>
                        DecisionMesh AI Control Plane
                    </Typography>

                    <Typography sx={{ mb: 3 }}>
                        Intent-centric AI orchestration platform
                    </Typography>

                    <Button
                        variant="contained"
                        fullWidth
                        sx={{ mb: 2 }}
                        onClick={() => keycloak.login()}
                    >
                        Login
                    </Button>

                    <Button
                        variant="outlined"
                        fullWidth
                        onClick={() =>
                            keycloak.register()
                        }
                    >
                        New User Sign Up
                    </Button>
                </CardContent>
            </Card>
        </Box>
    );
}