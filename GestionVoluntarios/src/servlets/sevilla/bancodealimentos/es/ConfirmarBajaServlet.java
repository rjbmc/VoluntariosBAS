package servlets.sevilla.bancodealimentos.es;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import com.google.gson.JsonObject;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import util.sevilla.bancodealimentos.es.DatabaseUtil;
import util.sevilla.bancodealimentos.es.LogUtil;
import util.sevilla.bancodealimentos.es.SharepointReplicationUtil;
import util.sevilla.bancodealimentos.es.SharepointUtil;

@WebServlet("/confirmar-baja")
public class ConfirmarBajaServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String token = request.getParameter("token");
        JsonObject jsonResponse = new JsonObject();
        Connection conn = null;

        if (token == null || token.trim().isEmpty()) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Token no proporcionado.");
            response.getWriter().write(jsonResponse.toString());
            return;
        }

        try {
            conn = DatabaseUtil.getConnection();
            conn.setAutoCommit(false);

            String usuario = null;
            String sqlRowUuid = null; // REPLICACIÓN SHAREPOINT: Variable para el UUID
            
            // REPLICACIÓN SHAREPOINT: Modificada la query para obtener también el SqlRowUUID
            String findUserSql = "SELECT Usuario, SqlRowUUID FROM voluntarios WHERE token_baja = ? AND token_baja_expiry > ?";
            try (PreparedStatement psFind = conn.prepareStatement(findUserSql)) {
                psFind.setString(1, token);
                psFind.setTimestamp(2, Timestamp.from(Instant.now()));
                
                try (ResultSet rs = psFind.executeQuery()) {
                    if (rs.next()) {
                        usuario = rs.getString("Usuario");
                        sqlRowUuid = rs.getString("SqlRowUUID"); // REPLICACIÓN SHAREPOINT: Capturar el UUID
                    }
                }
            }

            if (usuario != null) {
                String updateUserSql = "UPDATE voluntarios SET fecha_baja = ?, token_baja = NULL, token_baja_expiry = NULL WHERE Usuario = ?";
                try (PreparedStatement psUpdate = conn.prepareStatement(updateUserSql)) {
                    psUpdate.setTimestamp(1, Timestamp.from(Instant.now())); 
                    psUpdate.setString(2, usuario);
                    
                    int rowsAffected = psUpdate.executeUpdate();
                    
                    if (rowsAffected > 0) {
                        LogUtil.logOperation(conn, "BAJA-CONFIRM", usuario, "El usuario ha confirmado su baja.");
                        conn.commit();
                        
                        // --- INICIO: REPLICACIÓN A SHAREPOINT ---
                        if (sqlRowUuid != null) {
                            try {
                                // ** CORRECCIÓN: Añadido el Site ID como segundo parámetro **
                                SharepointReplicationUtil.replicate(conn, SharepointUtil.SP_SITE_ID_VOLUNTARIOS, "voluntarios", null, SharepointReplicationUtil.Operation.DELETE, sqlRowUuid);
                            } catch (Exception e) {
                                System.err.println("ADVERTENCIA: Fallo al iniciar el proceso de replicación (DELETE) a SharePoint para el UUID: " + sqlRowUuid + ". Causa: " + e.getMessage());
                            }
                        }
                        // --- FIN: REPLICACIÓN A SHAREPOINT ---

                        jsonResponse.addProperty("success", true);
                        jsonResponse.addProperty("message", "Tu cuenta ha sido dada de baja correctamente.");
                        response.getWriter().write(jsonResponse.toString());
                    } else {
                        throw new SQLException("No se pudo actualizar el registro del voluntario.");
                    }
                }
            } else {
                conn.rollback();
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                jsonResponse.addProperty("success", false);
                jsonResponse.addProperty("message", "El enlace de confirmación no es válido o ha caducado. Por favor, solicita la baja de nuevo.");
                response.getWriter().write(jsonResponse.toString());
            }

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            jsonResponse.addProperty("success", false);
            jsonResponse.addProperty("message", "Error de base de datos. Inténtalo más tarde.");
            response.getWriter().write(jsonResponse.toString());
            e.printStackTrace();
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException e) { e.printStackTrace(); }
        }
    }
}
