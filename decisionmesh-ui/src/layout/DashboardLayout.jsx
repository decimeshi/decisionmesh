import { useState } from "react";
import {
  AppBar, Toolbar, Typography, IconButton,
  Drawer, List, ListItemButton, ListItemText,
  Box, CssBaseline, Switch
} from "@mui/material";
import MenuIcon from "@mui/icons-material/Menu";

export default function DashboardLayout({
  children, keycloak, roles, onNavigate, toggleTheme, darkMode
}) {
  const [open, setOpen] = useState(false);

  return (
    <Box sx={{ display: "flex" }}>
      <CssBaseline />
      <AppBar position="fixed">
        <Toolbar>
          <IconButton color="inherit" onClick={() => setOpen(true)}>
            <MenuIcon />
          </IconButton>
          <Typography sx={{ flexGrow: 1 }}>
            DecisionMesh Control Plane
          </Typography>
          <Switch checked={darkMode} onChange={toggleTheme} />
          <Typography sx={{ marginRight: 2 }}>
            {keycloak.tokenParsed?.preferred_username}
          </Typography>
          <Typography sx={{ cursor: "pointer" }} onClick={() => keycloak.logout()}>
            Logout
          </Typography>
        </Toolbar>
      </AppBar>

      <Drawer open={open} onClose={() => setOpen(false)}>
        <Box sx={{ width: 240 }}>
          <List>
            <ListItemButton onClick={() => onNavigate("playground")}>
              <ListItemText primary="Intent Playground" />
            </ListItemButton>
            <ListItemButton onClick={() => onNavigate("viewer")}>
              <ListItemText primary="Intent Viewer" />
            </ListItemButton>
          </List>
        </Box>
      </Drawer>

      <Box component="main" sx={{ flexGrow: 1, p: 3, mt: 8 }}>
        {children}
      </Box>
    </Box>
  );
}
