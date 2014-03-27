package org.multibit.hd.brit.dto;

/**
 *  <p>DTO to provide the following to Payer and matcher:<br>
 *  <ul>
 *  <li>PGP encrypted version of MatcherResponse</li>
 *  </ul>
 *  </p>
 *  
 */
public class EncryptedMatcherResponse {
  /**
   * The encrypted payload
   */
  private byte[] payload;

  public EncryptedMatcherResponse(byte[] payload) {
    this.payload = payload;
  }

  public byte[] getPayload() {
    return payload;
  }
}
